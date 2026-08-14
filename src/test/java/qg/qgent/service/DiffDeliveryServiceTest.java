package qg.qgent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitCommitResponse;

import java.util.UUID;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import org.springframework.transaction.TransactionStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DiffDeliveryServiceTest {
    private final DiffMapper diffs = mock(DiffMapper.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
    private final SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
    private final DiffDeliveryService service = new DiffDeliveryService(diffs, workspaces, worktrees, worker,
            transactions, batches);

    @BeforeEach
    void executeTransactionCallbacks() {
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    @Test
    void nonBatchAcceptUsesVerifiedCommitAndPersistsRealSha() {
        UUID actor = UUID.randomUUID();
        TaskEntity task = task();
        DiffEntity diff = diff(task);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(task.getWorkspaceId()); workspace.setProjectId(task.getProjectId());
        when(diffs.selectById(diff.getId())).thenReturn(diff);
        when(diffs.selectByIdForUpdate(diff.getId())).thenReturn(diff);
        when(workspaces.selectByIdForUpdate(task.getWorkspaceId())).thenReturn(workspace);
        when(worktrees.updateHeadCommit(task.getWorkspaceId(), diff.getProjectRepositoryId(), "real-sha"))
                .thenReturn(1);
        WorkerGitCommitResponse committed = new WorkerGitCommitResponse(); committed.setCommitSha("real-sha");
        when(worker.commitWorkspaceDiff(eq(task.getWorkspaceId()), eq(diff.getProjectRepositoryId()), any()))
                .thenReturn(committed);

        DiffEntity result = service.acceptNonBatch(task, diff, actor);

        assertEquals("ACCEPTED", result.getStatus());
        assertEquals("COMMITTED", result.getDeliveryStatus());
        assertEquals("real-sha", result.getHeadCommit());
        verify(diffs, times(2)).updateById(diff);
    }

    @Test
    void workerFailureNeverMarksDiffAccepted() {
        TaskEntity task = task();
        DiffEntity diff = diff(task);
        when(diffs.selectById(diff.getId())).thenReturn(diff);
        when(diffs.selectByIdForUpdate(diff.getId())).thenReturn(diff);
        when(worker.commitWorkspaceDiff(any(), any(), any()))
                .thenThrow(new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                        "GIT_DIFF_MISMATCH", "stale"));

        assertThrows(ApiException.class, () -> service.acceptNonBatch(task, diff, UUID.randomUUID()));

        assertEquals("PENDING_REVIEW", diff.getStatus());
        assertEquals("FAILED", diff.getDeliveryStatus());
        assertEquals("GIT_DIFF_MISMATCH", diff.getDeliveryFailureCode());
        assertEquals("GIT_DIFF_MISMATCH", diff.getDeliveryFailureReason());
    }

    @Test
    void concurrentAcceptIsRejectedBeforeWorkerCall() {
        TaskEntity task = task(); DiffEntity diff = diff(task);
        diff.setDeliveryStatus("DELIVERING");
        diff.setDeliveryOperationId(UUID.randomUUID().toString());
        diff.setDeliveryLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        when(diffs.selectByIdForUpdate(diff.getId())).thenReturn(diff);

        ApiException error = assertThrows(ApiException.class,
                () -> service.acceptNonBatch(task, diff, UUID.randomUUID()));

        assertEquals("DIFF_DELIVERY_IN_PROGRESS", error.code());
        verifyNoInteractions(worker);
    }

    @Test
    void rejectCannotRaceAnAcceptedDiffWhileWorkerCommitIsInFlight() {
        TaskEntity task = task(); DiffEntity diff = diff(task);
        diff.setDeliveryStatus("DELIVERING");
        diff.setDeliveryOperationId(UUID.randomUUID().toString());
        when(diffs.selectByIdForUpdate(diff.getId())).thenReturn(diff);

        ApiException error = assertThrows(ApiException.class,
                () -> service.rejectNonBatch(task, diff, UUID.randomUUID(), "不接受"));

        assertEquals("DIFF_DELIVERY_IN_PROGRESS", error.code());
        assertEquals("PENDING_REVIEW", diff.getStatus());
        verify(diffs, never()).updateById(any(DiffEntity.class));
    }

    @Test
    void expiredClaimCanRecoverCommitAfterEarlierDatabaseWriteFailure() {
        TaskEntity task = task(); DiffEntity diff = diff(task);
        String operationId = UUID.randomUUID().toString();
        diff.setDeliveryStatus("DELIVERING"); diff.setDeliveryOperationId(operationId);
        diff.setDeliveryLeaseExpiresAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusSeconds(1));
        when(diffs.selectByIdForUpdate(diff.getId())).thenReturn(diff);
        when(diffs.selectById(diff.getId())).thenReturn(diff);
        WorkspaceEntity workspace = new WorkspaceEntity(); workspace.setId(task.getWorkspaceId());
        workspace.setProjectId(task.getProjectId());
        when(workspaces.selectByIdForUpdate(task.getWorkspaceId())).thenReturn(workspace);
        when(worktrees.updateHeadCommit(task.getWorkspaceId(), diff.getProjectRepositoryId(), "recovered-sha"))
                .thenReturn(1);
        WorkerGitCommitResponse response = new WorkerGitCommitResponse(); response.setCommitSha("recovered-sha");
        when(worker.commitWorkspaceDiff(any(), any(), any())).thenReturn(response);

        DiffEntity result = service.acceptNonBatch(task, diff, UUID.randomUUID());

        assertEquals("COMMITTED", result.getDeliveryStatus());
        verify(worker).commitWorkspaceDiff(any(), any(), argThat(request -> operationId.equals(request.getOperationId())));
    }

    @Test
    void expiredExecutorCannotPersistAfterNewClaimGetsFencingToken() {
        TaskEntity task = task();
        DiffEntity diff = diff(task);
        DiffEntity newer = diff(task);
        newer.setId(diff.getId()); newer.setProjectRepositoryId(diff.getProjectRepositoryId());
        newer.setDeliveryStatus("DELIVERING"); newer.setDeliveryClaimToken("new-owner-token");
        when(diffs.selectByIdForUpdate(diff.getId())).thenReturn(diff, newer);
        WorkerGitCommitResponse response = new WorkerGitCommitResponse();
        response.setCommitSha("stale-sha");
        when(worker.commitWorkspaceDiff(any(), any(), any())).thenReturn(response);

        ApiException error = assertThrows(ApiException.class,
                () -> service.acceptNonBatch(task, diff, UUID.randomUUID()));

        assertEquals("DIFF_DELIVERY_CLAIM_LOST", error.code());
        verify(worktrees, never()).updateHeadCommit(any(), any(), anyString());
    }

    private TaskEntity task() {
        TaskEntity task = new TaskEntity(); task.setId(UUID.randomUUID()); task.setProjectId(UUID.randomUUID());
        task.setWorkspaceId(UUID.randomUUID()); task.setTitle("change"); return task;
    }

    private DiffEntity diff(TaskEntity task) {
        DiffEntity diff = new DiffEntity(); diff.setId(UUID.randomUUID()); diff.setProjectId(task.getProjectId());
        diff.setTaskId(task.getId()); diff.setWorkspaceId(task.getWorkspaceId());
        diff.setProjectRepositoryId(UUID.randomUUID()); diff.setBaseCommit("base-sha");
        diff.setWorkingTreeHash("sha256:hash"); diff.setStatus("PENDING_REVIEW");
        diff.setDeliveryStatus("NOT_STARTED"); return diff;
    }
}
