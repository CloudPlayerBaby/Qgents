package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.TransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
import qg.qgent.api.ApiException;
import qg.qgent.dto.DiffReviewBatchResponse;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitDiff;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
                mock(DiffDeliveryService.class), mergeRequests, githubRepositories,
                mock(NotificationService.class));
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
    void rejectMarksTaskTerminalAndPublishesTaskUpdate() {
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        EventService events = mock(EventService.class);
        SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        TransactionTemplate transactions = immediateTransactions();
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID(), batchId = UUID.randomUUID(), diffId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID(), actor = UUID.randomUUID();

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setRequirementGroupId(groupId);
        task.setWorkspaceId(workspaceId);
        task.setCreatedBy(actor);
        task.setStatus("WAITING_DIFF_CONFIRMATION");
        task.setUpdatedAt(LocalDateTime.now());

        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId);
        batch.setProjectId(projectId);
        batch.setTaskId(taskId);
        batch.setWorkspaceId(workspaceId);
        batch.setReviewStatus("PENDING_CONFIRMATION");
        batch.setDeliveryStatus("NOT_STARTED");

        DiffEntity diff = new DiffEntity();
        diff.setId(diffId);
        diff.setProjectId(projectId);
        diff.setTaskId(taskId);
        diff.setTaskRunId(UUID.randomUUID());
        diff.setWorkspaceId(workspaceId);
        diff.setProjectRepositoryId(repositoryId);
        diff.setBaseCommit("base");
        diff.setSourceBranch("feat/rejected");
        diff.setHeadCommit("head");
        diff.setStatus("PENDING_REVIEW");
        diff.setCreatedAt(LocalDateTime.now());

        when(tasks.selectById(taskId)).thenReturn(task);
        when(tasks.selectByIdForUpdate(taskId)).thenReturn(task);
        when(access.isOwnerOrAdmin(actor, projectId, actor)).thenReturn(true);
        when(batches.selectOne(any())).thenReturn(batch);
        when(batches.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(diffs.selectList(any())).thenReturn(List.of(diff));

        DiffReviewBatchService service = new DiffReviewBatchService(batches, diffs, tasks,
                mock(ProjectRepositoryMapper.class), worker, mock(MergeRequestService.class),
                access, events, transactions, mock(DiffSnapshotStorage.class), mock(DiffDeliveryService.class),
                mock(MergeRequestMapper.class), mock(GitHubRepositoryMapper.class), mock(NotificationService.class));

        service.reject(projectId, taskId, actor, "需要补充异常场景");

        assertEquals("REJECTED", batch.getReviewStatus());
        assertEquals("REJECTED", diff.getStatus());
        assertEquals("DIFF_REJECTED", task.getStatus());
        verify(tasks).updateById(task);
        verify(events).publish(eq(projectId), eq(groupId), eq("task.updated"), eq(taskId.toString()), any());
        verify(events).publish(eq(projectId), eq(groupId), eq("diff-review.rejected"), eq(batchId.toString()), any());
        verifyNoInteractions(worker);
    }

    @Test
    void staleSnapshotFailsBatchDiffAndTask() {
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
        assertEquals("FAILED", batch.getDeliveryStatus());
        assertNull(batch.getDeliveryClaimToken());
        assertEquals("FAILED", diff.getDeliveryStatus());
        assertEquals("DELIVERY_FAILED", task.getStatus());
        verify(tasks).updateById(task);
        verify(diffs).updateById(diff);
        verify(batches, times(2)).updateById(batch);
        verify(worker).createWorkspaceGitDiff(workspaceId, repositoryId);
    }

    @Test
    void mrFirstStaleSnapshotNeverCommitsOrPushesUnreviewedWorkspaceChanges() {
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TransactionTemplate transactions = immediateTransactions();
        SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
        DiffDeliveryService deliveries = mock(DiffDeliveryService.class);
        MergeRequestService mergeRequests = mock(MergeRequestService.class);
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        task.setDeliveryMode("MR_FIRST"); task.setStatus("DELIVERING");
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId); batch.setProjectId(projectId); batch.setTaskId(taskId);
        batch.setWorkspaceId(workspaceId); batch.setReviewStatus("ACCEPTED");
        batch.setConfirmationSource("SYSTEM"); batch.setDeliveryStatus("DELIVERING");
        batch.setDeliveryClaimToken("claim");
        DiffEntity diff = new DiffEntity();
        diff.setId(UUID.randomUUID()); diff.setProjectId(projectId); diff.setTaskId(taskId);
        diff.setWorkspaceId(workspaceId); diff.setReviewBatchId(batchId); diff.setProjectRepositoryId(repositoryId);
        diff.setHeadCommit("head"); diff.setWorkingTreeHash("reviewed-hash"); diff.setStatus("ACCEPTED");
        diff.setDeliveryStatus("NOT_STARTED");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(batches.selectById(batchId)).thenReturn(batch);
        when(batches.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(diffs.selectList(any())).thenReturn(List.of(diff));
        when(diffs.selectByIdForUpdate(diff.getId())).thenReturn(diff);
        when(worker.createWorkspaceGitDiff(workspaceId, repositoryId)).thenThrow(new ApiException(HttpStatus.CONFLICT,
                "DIFF_SNAPSHOT_STALE", "workspace changed after final Diff"));
        DiffReviewBatchService service = new DiffReviewBatchService(batches, diffs, tasks,
                mock(ProjectRepositoryMapper.class), worker, mergeRequests, mock(ProjectAccessService.class),
                mock(EventService.class), transactions, mock(DiffSnapshotStorage.class), deliveries,
                mock(MergeRequestMapper.class), mock(GitHubRepositoryMapper.class), mock(NotificationService.class));

        service.deliverSystemAcceptedBatch(projectId, taskId, batchId, "claim");

        assertEquals("DELIVERY_FAILED", task.getStatus());
        assertEquals("FAILED", batch.getDeliveryStatus());
        assertEquals("FAILED", diff.getDeliveryStatus());
        verifyNoInteractions(deliveries, mergeRequests);
    }

    @Test
    void mrFirstDeliveryHoldsWorkspaceWriteLeaseUntilWorkerPreflightFinishes() {
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TransactionTemplate transactions = immediateTransactions();
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        task.setDeliveryMode("MR_FIRST"); task.setStatus("DELIVERING");
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId); batch.setProjectId(projectId); batch.setTaskId(taskId);
        batch.setReviewStatus("ACCEPTED"); batch.setConfirmationSource("SYSTEM");
        batch.setDeliveryStatus("DELIVERING"); batch.setDeliveryClaimToken("claim");
        DiffEntity diff = new DiffEntity();
        diff.setId(UUID.randomUUID()); diff.setProjectId(projectId); diff.setTaskId(taskId);
        diff.setWorkspaceId(workspaceId); diff.setReviewBatchId(batchId); diff.setProjectRepositoryId(repositoryId);
        diff.setHeadCommit("head"); diff.setWorkingTreeHash("hash"); diff.setDeliveryStatus("COMMITTED");
        SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
        WorkerGitDiff current = new WorkerGitDiff();
        current.setHeadCommit("head"); current.setDiffHash("hash");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(batches.selectById(batchId)).thenReturn(batch);
        when(batches.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(diffs.selectList(any())).thenReturn(List.of(diff));
        when(diffs.selectByIdForUpdate(diff.getId())).thenReturn(diff);
        doAnswer(invocation -> {
            diff.setDeliveryStatus("PUSHED");
            return 1;
        }).when(diffs).markPushed(eq(diff.getId()), any());
        when(worker.createWorkspaceGitDiff(workspaceId, repositoryId)).thenReturn(current);
        DiffReviewBatchService service = new DiffReviewBatchService(batches, diffs, tasks,
                mock(ProjectRepositoryMapper.class), worker, mock(MergeRequestService.class),
                mock(ProjectAccessService.class), mock(EventService.class), transactions,
                mock(DiffSnapshotStorage.class), mock(DiffDeliveryService.class), mock(MergeRequestMapper.class),
                mock(GitHubRepositoryMapper.class), mock(NotificationService.class));
        WorkspaceWriteLeaseService writeLeases = mock(WorkspaceWriteLeaseService.class);
        WorkspaceWriteLease lease = mock(WorkspaceWriteLease.class);
        when(writeLeases.acquire(projectId, workspaceId, taskId)).thenReturn(lease);
        service.setWorkspaceWriteLeases(writeLeases);

        service.deliverSystemAcceptedBatch(projectId, taskId, batchId, "claim");

        verify(writeLeases).acquire(projectId, workspaceId, taskId);
        verify(writeLeases).renew(lease);
        verify(writeLeases).release(lease);
    }

    @Test
    void retryOfCommittedRepositoryOnlyPushesAndDoesNotPreflightOldWorkingTree() {
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
        MergeRequestService mergeRequests = mock(MergeRequestService.class);
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID(), repositoryId = UUID.randomUUID(), actor = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        task.setCreatedBy(actor); task.setStatus("DELIVERY_FAILED");
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId); batch.setProjectId(projectId); batch.setTaskId(taskId);
        batch.setReviewStatus("ACCEPTED"); batch.setDeliveryStatus("FAILED");
        DiffEntity committed = new DiffEntity();
        committed.setId(UUID.randomUUID()); committed.setProjectId(projectId); committed.setTaskId(taskId);
        committed.setTaskRunId(UUID.randomUUID()); committed.setCreatedAt(LocalDateTime.now());
        committed.setWorkspaceId(workspaceId); committed.setReviewBatchId(batchId);
        committed.setProjectRepositoryId(repositoryId); committed.setStatus("ACCEPTED");
        committed.setDeliveryStatus("COMMITTED"); committed.setHeadCommit("committed-head");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(access.isOwnerOrAdmin(actor, projectId, actor)).thenReturn(true);
        when(batches.selectOne(any())).thenReturn(batch);
        when(batches.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(batches.selectById(batchId)).thenReturn(batch);
        when(diffs.selectList(any())).thenReturn(List.of(committed));
        when(diffs.selectByIdForUpdate(committed.getId())).thenReturn(committed);
        doAnswer(invocation -> {
            committed.setDeliveryStatus("PUSHED");
            return 1;
        }).when(diffs).markPushed(eq(committed.getId()), any());
        DiffReviewBatchService service = new DiffReviewBatchService(batches, diffs, tasks,
                mock(ProjectRepositoryMapper.class), worker, mergeRequests, access, mock(EventService.class),
                immediateTransactions(), mock(DiffSnapshotStorage.class), mock(DiffDeliveryService.class),
                mock(MergeRequestMapper.class), mock(GitHubRepositoryMapper.class), mock(NotificationService.class));

        service.retryDelivery(projectId, taskId, actor);

        verifyNoInteractions(worker);
        verify(mergeRequests).pushAcceptedBranch(projectId, taskId, repositoryId);
        assertEquals("PUSHED", committed.getDeliveryStatus());
        assertEquals("DELIVERED", batch.getDeliveryStatus());
        assertEquals("SUCCEEDED", task.getStatus());
    }

    @Test
    void pushFailureAfterCommitKeepsCommittedStateForRetry() {
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        ProjectAccessService access = mock(ProjectAccessService.class);
        SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
        MergeRequestService mergeRequests = mock(MergeRequestService.class);
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID(), repositoryId = UUID.randomUUID(), actor = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        task.setCreatedBy(actor); task.setStatus("DELIVERY_FAILED");
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId); batch.setProjectId(projectId); batch.setTaskId(taskId);
        batch.setReviewStatus("ACCEPTED"); batch.setDeliveryStatus("FAILED");
        DiffEntity committed = new DiffEntity();
        committed.setId(UUID.randomUUID()); committed.setProjectId(projectId); committed.setTaskId(taskId);
        committed.setTaskRunId(UUID.randomUUID()); committed.setCreatedAt(LocalDateTime.now());
        committed.setWorkspaceId(workspaceId); committed.setReviewBatchId(batchId);
        committed.setProjectRepositoryId(repositoryId); committed.setStatus("ACCEPTED");
        committed.setDeliveryStatus("COMMITTED"); committed.setHeadCommit("committed-head");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(access.isOwnerOrAdmin(actor, projectId, actor)).thenReturn(true);
        when(batches.selectOne(any())).thenReturn(batch);
        when(batches.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(batches.selectById(batchId)).thenReturn(batch);
        when(diffs.selectList(any())).thenReturn(List.of(committed));
        when(diffs.selectByIdForUpdate(committed.getId())).thenReturn(committed);
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "WORKER_PUSH_FAILED", "push failed"))
                .when(mergeRequests).pushAcceptedBranch(projectId, taskId, repositoryId);
        DiffReviewBatchService service = new DiffReviewBatchService(batches, diffs, tasks,
                mock(ProjectRepositoryMapper.class), worker, mergeRequests, access, mock(EventService.class),
                immediateTransactions(), mock(DiffSnapshotStorage.class), mock(DiffDeliveryService.class),
                mock(MergeRequestMapper.class), mock(GitHubRepositoryMapper.class), mock(NotificationService.class));

        service.retryDelivery(projectId, taskId, actor);

        verifyNoInteractions(worker);
        assertEquals("COMMITTED", committed.getDeliveryStatus());
        assertEquals("WORKER_PUSH_FAILED", committed.getDeliveryFailureCode());
        assertEquals("DELIVERY_FAILED", task.getStatus());
    }

    @Test
    void transientPreflightFailureRestoresPendingBatchForRetry() {
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TransactionTemplate transactions = immediateTransactions();
        ProjectAccessService access = mock(ProjectAccessService.class);
        SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID(), repositoryId = UUID.randomUUID(), actor = UUID.randomUUID();
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId);
        task.setWorkspaceId(workspaceId); task.setCreatedBy(actor); task.setStatus("WAITING_DIFF_CONFIRMATION");
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
        when(diffs.selectList(any())).thenReturn(List.of(diff));
        when(worker.createWorkspaceGitDiff(workspaceId, repositoryId))
                .thenThrow(new IllegalStateException("worker unavailable"));
        DiffReviewBatchService service = service(batches, diffs, tasks, worker, access, transactions);

        assertThrows(IllegalStateException.class, () -> service.confirm(projectId, taskId, actor));
        assertEquals("PENDING_CONFIRMATION", batch.getReviewStatus());
        assertEquals("NOT_STARTED", batch.getDeliveryStatus());
        assertNull(batch.getDeliveryClaimToken());
        assertEquals("WAITING_DIFF_CONFIRMATION", task.getStatus());
        verify(tasks, never()).updateById(task);
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

    @Test
    void finishNotifiesOnlyWhenTaskTerminalStatusChanges() {
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TransactionTemplate transactions = immediateTransactions();
        NotificationService notifications = mock(NotificationService.class);
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), batchId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setCreatedBy(actor); task.setStatus("DELIVERING");
        task.setTitle("登录接口"); task.setRequirement("实现登录接口");
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId); batch.setProjectId(projectId); batch.setTaskId(taskId);
        batch.setReviewStatus("ACCEPTED"); batch.setDeliveryStatus("DELIVERING");
        batch.setDeliveryClaimToken("claim"); batch.setUpdatedAt(LocalDateTime.now());
        DiffEntity diff = new DiffEntity(); diff.setId(UUID.randomUUID());
        diff.setReviewBatchId(batchId); diff.setDeliveryStatus("PUSHED");
        when(batches.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(diffs.selectList(any())).thenReturn(List.of(diff));
        DiffReviewBatchService service = service(batches, diffs, tasks, mock(SandboxWorkerClient.class),
                mock(ProjectAccessService.class), transactions, notifications);

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "finish", task, batchId, "claim");

        verify(notifications).notify(actor, projectId, null, "TASK_COMPLETED", "任务完成：登录接口",
                "实现登录接口", taskId.toString());
        assertEquals("SUCCEEDED", task.getStatus());
    }

    @Test
    void mrFirstPushCompletionWaitsForPreflightWithoutFailureNotification() {
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TransactionTemplate transactions = immediateTransactions();
        NotificationService notifications = mock(NotificationService.class);
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), batchId = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        UUID groupId = UUID.randomUUID();
        UUID orchestratorId = UUID.randomUUID();
        task.setId(taskId); task.setProjectId(projectId); task.setCreatedBy(UUID.randomUUID());
        task.setRequirementGroupId(groupId);
        task.setDeliveryMode("MR_FIRST"); task.setStatus("DELIVERING"); task.setTitle("登录接口");
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId); batch.setProjectId(projectId); batch.setTaskId(taskId);
        batch.setReviewStatus("ACCEPTED"); batch.setDeliveryStatus("DELIVERING");
        batch.setDeliveryClaimToken("claim"); batch.setUpdatedAt(LocalDateTime.now());
        DiffEntity diff = new DiffEntity(); diff.setId(UUID.randomUUID());
        diff.setReviewBatchId(batchId); diff.setDeliveryStatus("PUSHED");
        when(batches.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(diffs.selectList(any())).thenReturn(List.of(diff));
        MessageService messages = mock(MessageService.class);
        OrchestratorAgentService orchestratorAgents = mock(OrchestratorAgentService.class);
        when(orchestratorAgents.resolveIdForTask(task)).thenReturn(orchestratorId);
        DiffReviewBatchService service = service(batches, diffs, tasks, mock(SandboxWorkerClient.class),
                mock(ProjectAccessService.class), transactions, notifications);
        service.setMessageService(messages);
        service.setOrchestratorAgents(orchestratorAgents);

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "finish", task, batchId, "claim");

        assertEquals("WAITING_PREFLIGHT", task.getStatus());
        verify(notifications, never()).notify(any(), any(), any(), any(), any(), any(), any());
        verify(messages).sendAsAgent(eq(groupId), eq(orchestratorId), argThat(body ->
                "TASK_STATUS".equals(body.getType())
                        && "agent-task-".concat(taskId.toString()).concat("-waiting-preflight")
                        .equals(body.getClientMessageId())
                        && "WAITING_PREFLIGHT".equals(body.getContent().get("status"))
                        && "MR_FIRST".equals(body.getContent().get("deliveryMode"))));
    }

    @Test
    void mrFirstPreflightCardFallsBackToSystemWithoutOrchestratorAgent() {
        DiffReviewBatchMapper batches = mock(DiffReviewBatchMapper.class);
        DiffMapper diffs = mock(DiffMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        TransactionTemplate transactions = immediateTransactions();
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), batchId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setRequirementGroupId(groupId);
        task.setDeliveryMode("MR_FIRST"); task.setStatus("DELIVERING");
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(batchId); batch.setProjectId(projectId); batch.setTaskId(taskId);
        batch.setReviewStatus("ACCEPTED"); batch.setDeliveryStatus("DELIVERING");
        batch.setDeliveryClaimToken("claim"); batch.setUpdatedAt(LocalDateTime.now());
        DiffEntity diff = new DiffEntity(); diff.setId(UUID.randomUUID());
        diff.setReviewBatchId(batchId); diff.setDeliveryStatus("PUSHED");
        when(batches.selectByIdForUpdate(batchId)).thenReturn(batch);
        when(diffs.selectList(any())).thenReturn(List.of(diff));
        MessageService messages = mock(MessageService.class);
        OrchestratorAgentService orchestratorAgents = mock(OrchestratorAgentService.class);
        when(orchestratorAgents.resolveIdForTask(task)).thenReturn(null);
        doThrow(new RuntimeException("message store unavailable")).when(messages).sendAsSystem(eq(groupId), any());
        DiffReviewBatchService service = service(batches, diffs, tasks, mock(SandboxWorkerClient.class),
                mock(ProjectAccessService.class), transactions, mock(NotificationService.class));
        service.setMessageService(messages);
        service.setOrchestratorAgents(orchestratorAgents);

        assertDoesNotThrow(() -> org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(service, "finish", task, batchId, "claim"));

        assertEquals("WAITING_PREFLIGHT", task.getStatus());
        verify(messages).sendAsSystem(eq(groupId), argThat(body ->
                "TASK_STATUS".equals(body.getType())
                        && "WAITING_PREFLIGHT".equals(body.getContent().get("status"))));
        verify(messages, never()).sendAsAgent(any(), any(), any());
    }

    private DiffReviewBatchService service(DiffReviewBatchMapper batches, DiffMapper diffs, TaskMapper tasks,
            SandboxWorkerClient worker, ProjectAccessService access, TransactionTemplate transactions) {
        return service(batches, diffs, tasks, worker, access, transactions, mock(NotificationService.class));
    }

    private DiffReviewBatchService service(DiffReviewBatchMapper batches, DiffMapper diffs, TaskMapper tasks,
            SandboxWorkerClient worker, ProjectAccessService access, TransactionTemplate transactions,
            NotificationService notifications) {
        return new DiffReviewBatchService(batches, diffs, tasks, mock(ProjectRepositoryMapper.class), worker,
                mock(MergeRequestService.class), access, mock(EventService.class), transactions,
                mock(DiffSnapshotStorage.class), mock(DiffDeliveryService.class), mock(MergeRequestMapper.class),
                mock(GitHubRepositoryMapper.class), notifications);
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
