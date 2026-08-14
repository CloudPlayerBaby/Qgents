package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import qg.qgent.dto.DiffReviewBatchResponse;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;
import qg.qgent.orchestration.worker.SandboxWorkerClient;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DiffReviewBatchServiceTest {
    @Test
    void responseContainsRepositoryDeliveryAndRealMergeRequestSummary() {
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
        MergeRequestMapper mergeRequests = mock(MergeRequestMapper.class);
        GitHubRepositoryMapper githubRepositories = mock(GitHubRepositoryMapper.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        DiffReviewBatchService service = new DiffReviewBatchService(batches, diffs, mock(TaskMapper.class),
                repositories, mock(SandboxWorkerClient.class), mock(MergeRequestService.class), access,
                mock(EventService.class), mock(TransactionTemplate.class), mock(DiffSnapshotStorage.class),
                mock(DiffDeliveryService.class), mergeRequests, githubRepositories);
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), batchId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID(), githubId = UUID.randomUUID(), diffId = UUID.randomUUID();
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId); batch.setProjectId(projectId); batch.setTaskId(taskId);
        UUID workspaceId = UUID.randomUUID(); batch.setWorkspaceId(workspaceId);
        batch.setReviewStatus("ACCEPTED"); batch.setDeliveryStatus("DELIVERED"); batch.setAggregateHash("hash");
        when(batches.selectOne(any())).thenReturn(batch);
        DiffEntity diff = new DiffEntity(); diff.setId(diffId); diff.setProjectId(projectId); diff.setTaskId(taskId);
        diff.setTaskRunId(UUID.randomUUID()); diff.setWorkspaceId(workspaceId);
        diff.setProjectRepositoryId(repositoryId); diff.setBaseCommit("base"); diff.setSourceBranch("feat/x");
        diff.setHeadCommit("head"); diff.setStatus("ACCEPTED"); diff.setDeliveryStatus("MR_CREATED");
        diff.setCreatedAt(LocalDateTime.now()); diff.setUpdatedAt(LocalDateTime.now());
        when(diffs.selectList(any())).thenReturn(List.of(diff));
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId); repository.setProjectId(projectId); repository.setRepositoryId(githubId);
        repository.setDisplayName("backend");
        when(repositories.selectBatchIds(any(Collection.class))).thenReturn(List.of(repository));
        MergeRequestEntity mr = new MergeRequestEntity(); mr.setId(UUID.randomUUID()); mr.setTaskId(taskId);
        mr.setProjectRepositoryId(repositoryId); mr.setProviderNumber(128L); mr.setTitle("feat: x"); mr.setStatus("OPEN");
        mr.setWorkspaceId(workspaceId); mr.setSourceBranch("feat/x"); mr.setHeadCommit("head");
        when(mergeRequests.selectList(any())).thenReturn(List.of(mr));
        GitHubRepositoryEntity github = new GitHubRepositoryEntity(); github.setId(githubId);
        github.setOwnerLogin("qgents"); github.setName("backend");
        when(githubRepositories.selectBatchIds(any(Collection.class))).thenReturn(List.of(github));

        DiffReviewBatchResponse response = service.get(projectId, taskId, UUID.randomUUID());

        assertEquals(1, response.getRepositoryDeliveries().size());
        assertEquals("MR_CREATED", response.getRepositoryDeliveries().getFirst().getDeliveryStatus());
        assertEquals("https://github.com/qgents/backend/pull/128",
                response.getRepositoryDeliveries().getFirst().getMergeRequest().getWebUrl());
    }

    @Test
    void preflightFailureRestoresPendingBatchSoUserCanReject() {
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TransactionTemplate transactions = immediateTransactions();
        ProjectAccessService access = mock(ProjectAccessService.class);
        SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID(), repositoryId = UUID.randomUUID(), actor = UUID.randomUUID();
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId);
        task.setWorkspaceId(workspaceId); task.setCreatedBy(actor);
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity(); batch.setId(batchId); batch.setProjectId(projectId);
        batch.setTaskId(taskId); batch.setWorkspaceId(workspaceId); batch.setReviewStatus("PENDING_CONFIRMATION");
        batch.setDeliveryStatus("NOT_STARTED"); batch.setAggregateHash("hash");
        DiffEntity diff = new DiffEntity(); diff.setId(UUID.randomUUID()); diff.setProjectId(projectId);
        diff.setTaskId(taskId); diff.setTaskRunId(UUID.randomUUID()); diff.setWorkspaceId(workspaceId);
        diff.setProjectRepositoryId(repositoryId); diff.setHeadCommit("head"); diff.setWorkingTreeHash("hash");
        diff.setBaseCommit("base"); diff.setSourceBranch("feat/x"); diff.setStatus("PENDING_REVIEW");
        diff.setDeliveryStatus("NOT_STARTED"); diff.setCreatedAt(LocalDateTime.now());
        when(tasks.selectById(taskId)).thenReturn(task);
        when(access.isOwnerOrAdmin(actor, projectId, actor)).thenReturn(true);
        when(batches.selectOne(any())).thenReturn(batch);
        when(batches.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(batches.selectById(batchId)).thenReturn(batch);
        when(diffs.selectList(any())).thenReturn(List.of(diff));
        when(worker.createWorkspaceGitDiff(workspaceId, repositoryId))
                .thenThrow(new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                        "DIFF_SNAPSHOT_STALE", "stale"));
        DiffReviewBatchService service = service(batches, diffs, tasks, worker, access, transactions);

        assertThrows(qg.qgent.api.ApiException.class, () -> service.confirm(projectId, taskId, actor));
        assertEquals("PENDING_CONFIRMATION", batch.getReviewStatus());
        assertEquals("NOT_STARTED", batch.getDeliveryStatus());
        assertNull(batch.getDeliveryClaimToken());

        DiffReviewBatchResponse rejected = service.reject(projectId, taskId, actor, "needs changes");
        assertEquals("REJECTED", rejected.getReviewStatus());
    }

    @Test
    void expiredBatchExecutorCannotRenewAfterNewFencingClaim() {
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TransactionTemplate transactions = immediateTransactions();
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), batchId = UUID.randomUUID();
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId);
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity(); batch.setId(batchId); batch.setProjectId(projectId);
        batch.setTaskId(taskId); batch.setReviewStatus("PENDING_CONFIRMATION"); batch.setDeliveryStatus("DELIVERING");
        batch.setDeliveryLeaseExpiresAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        when(batches.selectOne(any())).thenReturn(batch);
        when(batches.selectByIdForUpdate(batchId)).thenReturn(batch);
        DiffReviewBatchService service = service(batches, diffs, tasks, mock(SandboxWorkerClient.class),
                mock(ProjectAccessService.class), transactions);

        ReflectionTestUtils.invokeMethod(service, "claim", task);
        String oldToken = batch.getDeliveryClaimToken();
        batch.setDeliveryLeaseExpiresAt(LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1));
        ReflectionTestUtils.invokeMethod(service, "claim", task);
        String newToken = batch.getDeliveryClaimToken();

        assertNotEquals(oldToken, newToken);
        qg.qgent.api.ApiException error = assertThrows(qg.qgent.api.ApiException.class,
                () -> ReflectionTestUtils.invokeMethod(service, "renewBatchLease", batchId, oldToken));
        assertEquals("DIFF_DELIVERY_CLAIM_LOST", error.code());
    }

    private DiffReviewBatchService service(DiffReviewBatchMapper batches, DiffMapper diffs, TaskMapper tasks,
            SandboxWorkerClient worker, ProjectAccessService access, TransactionTemplate transactions) {
        return new DiffReviewBatchService(batches, diffs, tasks, mock(ProjectRepositoryMapper.class), worker,
                mock(MergeRequestService.class), access, mock(EventService.class), transactions,
                mock(DiffSnapshotStorage.class), mock(DiffDeliveryService.class), mock(MergeRequestMapper.class),
                mock(GitHubRepositoryMapper.class));
    }

    private TransactionTemplate immediateTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation ->
                ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null); return null;
        }).when(transactions).executeWithoutResult(any());
        return transactions;
    }
}
