package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.DiffFileMapper;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitDiff;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinalDiffBundleServiceTest {
    @Test
    void systemAcceptedBatchPersistsDeliveryIntentAndPublishesItsBatchId() {
        TaskMapper tasks = mock(TaskMapper.class);
        TaskRunMapper runs = mock(TaskRunMapper.class);
        WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
        DiffSnapshotStorage snapshots = mock(DiffSnapshotStorage.class);
        EventService events = mock(EventService.class);
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setWorkspaceId(workspaceId);
        task.setDeliveryMode("MR_FIRST");
        TaskRunEntity coding = new TaskRunEntity();
        coding.setId(UUID.randomUUID());
        coding.setTaskId(taskId);
        coding.setRole("DEVELOPER");
        coding.setStatus("SUCCEEDED");
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setBaseCommit("a".repeat(40));
        worktree.setSourceBranch("feat/mr-first");
        WorkerGitDiff workerDiff = new WorkerGitDiff();
        workerDiff.setBaseCommit("a".repeat(40));
        workerDiff.setHeadCommit("b".repeat(40));
        workerDiff.setDiffHash("sha256:" + "c".repeat(64));
        workerDiff.setPatch("diff --git a/a b/a");
        workerDiff.setFiles(List.of());
        when(tasks.selectById(taskId)).thenReturn(task);
        when(runs.selectById(coding.getId())).thenReturn(coding);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(worker.createWorkspaceGitDiff(workspaceId, repositoryId)).thenReturn(workerDiff);
        when(batches.selectOne(any())).thenReturn(null);
        when(snapshots.store(any(), any())).thenReturn("snapshot-key");

        FinalDiffBundleService service = new FinalDiffBundleService(tasks, runs, worktrees, batches, diffs,
                mock(DiffFileMapper.class), worker, snapshots, events, immediateTransactions());

        UUID batchId = service.createSystemAcceptedBatch(projectId, taskId, coding.getId());

        ArgumentCaptor<DiffReviewBatchEntity> batch = ArgumentCaptor.forClass(DiffReviewBatchEntity.class);
        verify(batches).insert(batch.capture());
        assertEquals(batch.getValue().getId(), batchId);
        assertEquals("ACCEPTED", batch.getValue().getReviewStatus());
        assertEquals("SYSTEM", batch.getValue().getConfirmationSource());
        assertEquals("DELIVERING", batch.getValue().getDeliveryStatus());
        assertNotNull(batch.getValue().getDeliveryOperationId());
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(events).publish(eq(projectId), any(), eq("delivery.started"), eq(taskId.toString()), payload.capture());
        assertEquals(batchId, payload.getValue().get("reviewBatchId"));
        assertEquals(batch.getValue().getDeliveryOperationId(), payload.getValue().get("operationId"));
    }

    @SuppressWarnings("unchecked")
    private TransactionTemplate immediateTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<Object> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return transactions;
    }
}
