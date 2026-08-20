package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.dto.DryRunResponse;
import qg.qgent.dto.MergeRequestPreflightResponse;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.MrPreflightRequestEntity;
import qg.qgent.entity.MrPreflightTaskEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.MrPreflightRequestMapper;
import qg.qgent.mapper.MrPreflightTaskMapper;
import qg.qgent.mapper.PreflightCqReviewMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MrPreflightServiceTest {
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
    private final ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
    private final MrPreflightRequestMapper preflightRequests = mock(MrPreflightRequestMapper.class);
    private final MrPreflightTaskMapper preflightTasks = mock(MrPreflightTaskMapper.class);
    private final MergeRequestMapper mergeRequests = mock(MergeRequestMapper.class);
    private final DryRunMapper dryRuns = mock(DryRunMapper.class);
    private final PreflightCqReviewMapper cqReviews = mock(PreflightCqReviewMapper.class);
    private final DiffMapper diffs = mock(DiffMapper.class);
    private final TestRunService testRuns = mock(TestRunService.class);
    private final GitStoreSyncService gitStores = mock(GitStoreSyncService.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService events = mock(EventService.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final MrPreflightService service = new MrPreflightService(tasks, worktrees, repositories,
            preflightRequests, preflightTasks, mergeRequests, dryRuns, cqReviews, diffs, testRuns,
            gitStores, access, events, transactions);

    @Test
    void requestPreflightOnPushedDiffFirstBranchPersistsRequestAndStartsDryRun() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID dryRunId = UUID.randomUUID();
        String sourceBranch = "feat/task";
        String headCommit = "0123456789012345678901234567890123456789";
        String targetCommit = "abcdef0123456789abcdef0123456789abcdef01";

        TaskEntity task = task(projectId, taskId, workspaceId, userId);
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, sourceBranch, headCommit, "main");
        ProjectRepositoryEntity repository = repository(projectId, repositoryId, "main");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(repositories.selectById(repositoryId)).thenReturn(repository);
        when(gitStores.normalizeTargetBranch("main")).thenReturn("main");
        when(gitStores.refreshTargetBranch(projectId, repository, "main")).thenReturn(targetCommit);
        when(mergeRequests.selectOne(any())).thenReturn(null);
        when(preflightRequests.selectByContextHash(MrPreflightService.contextHash(repositoryId, sourceBranch, "main", headCommit, targetCommit))).thenReturn(null);
        when(tasks.selectDeliveredTasksOnBranch(projectId, repositoryId, sourceBranch)).thenReturn(List.of(taskId));
        when(testRuns.createAutomaticDryRun(projectId, taskId, repositoryId, "main"))
                .thenReturn(dryRunResponse(dryRunId, targetCommit));
        runTransactionCallback();
        when(preflightRequests.selectByIdForUpdate(any())).thenAnswer(invocation -> {
            MrPreflightRequestEntity request = new MrPreflightRequestEntity();
            request.setId(invocation.getArgument(0));
            request.setDryRunId(null);
            request.setProjectId(projectId);
            request.setTriggerTaskId(taskId);
            return request;
        });
        when(preflightRequests.selectById(any())).thenAnswer(invocation -> {
            MrPreflightRequestEntity request = new MrPreflightRequestEntity();
            request.setId(invocation.getArgument(0));
            request.setDryRunId(dryRunId);
            request.setProjectId(projectId);
            request.setTriggerTaskId(taskId);
            request.setProjectRepositoryId(repositoryId);
            request.setSourceBranch(sourceBranch);
            request.setTargetBranch("main");
            request.setHeadCommit(headCommit);
            request.setTargetCommit(targetCommit);
            request.setStatus("DRY_RUN_QUEUED");
            return request;
        });
        DryRunEntity queuedDryRun = new DryRunEntity();
        queuedDryRun.setStatus("QUEUED");
        when(dryRuns.selectById(dryRunId)).thenReturn(queuedDryRun);
        MrPreflightTaskEntity link = new MrPreflightTaskEntity();
        link.setPreflightId(UUID.randomUUID());
        link.setTaskId(taskId);
        link.setRole("TRIGGER");
        when(preflightTasks.selectByPreflight(any())).thenReturn(List.of(link));
        when(diffs.selectDeliveredDiffIds(projectId, repositoryId, List.of(taskId))).thenReturn(List.of());

        MergeRequestPreflightResponse response = service.requestPreflight(projectId, userId, taskId, repositoryId, null);

        assertEquals("DRY_RUN_QUEUED", response.getStatus());
        assertEquals(dryRunId.toString(), response.getDryRunId());
        assertEquals(sourceBranch, response.getSourceBranch());
        assertEquals(targetCommit, response.getTargetCommit());
        verify(preflightRequests).insert(any(MrPreflightRequestEntity.class));
        verify(testRuns).createAutomaticDryRun(projectId, taskId, repositoryId, "main");
    }

    @Test
    void duplicateContextReturnsExistingPreflightWithoutSecondDryRun() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID preflightId = UUID.randomUUID();
        UUID dryRunId = UUID.randomUUID();
        String sourceBranch = "feat/task";
        String headCommit = "0123456789012345678901234567890123456789";
        String targetCommit = "abcdef0123456789abcdef0123456789abcdef01";

        TaskEntity task = task(projectId, taskId, workspaceId, userId);
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, sourceBranch, headCommit, "main");
        ProjectRepositoryEntity repository = repository(projectId, repositoryId, "main");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(repositories.selectById(repositoryId)).thenReturn(repository);
        when(gitStores.normalizeTargetBranch("main")).thenReturn("main");
        when(gitStores.refreshTargetBranch(projectId, repository, "main")).thenReturn(targetCommit);
        when(mergeRequests.selectOne(any())).thenReturn(null);

        MrPreflightRequestEntity existing = new MrPreflightRequestEntity();
        existing.setId(preflightId);
        existing.setProjectId(projectId);
        existing.setTriggerTaskId(taskId);
        existing.setProjectRepositoryId(repositoryId);
        existing.setSourceBranch(sourceBranch);
        existing.setTargetBranch("main");
        existing.setHeadCommit(headCommit);
        existing.setTargetCommit(targetCommit);
        existing.setDryRunId(dryRunId);
        existing.setStatus("WAITING_CQ");
        when(preflightRequests.selectByContextHash(MrPreflightService.contextHash(repositoryId, sourceBranch, "main", headCommit, targetCommit)))
                .thenReturn(existing);
        DryRunEntity passedDryRun = new DryRunEntity();
        passedDryRun.setStatus("PASSED");
        when(dryRuns.selectById(dryRunId)).thenReturn(passedDryRun);
        MrPreflightTaskEntity link = new MrPreflightTaskEntity();
        link.setPreflightId(preflightId);
        link.setTaskId(taskId);
        link.setRole("TRIGGER");
        when(preflightTasks.selectByPreflight(preflightId)).thenReturn(List.of(link));
        when(diffs.selectDeliveredDiffIds(projectId, repositoryId, List.of(taskId))).thenReturn(List.of());

        MergeRequestPreflightResponse response = service.requestPreflight(projectId, userId, taskId, repositoryId, null);

        assertEquals("WAITING_CQ", response.getStatus());
        assertEquals(preflightId.toString(), response.getId());
        verify(preflightRequests, never()).insert(any(MrPreflightRequestEntity.class));
        verify(testRuns, never()).createAutomaticDryRun(any(), any(), any(), any());
    }

    @Test
    void retryPreflightAfterCqRejectionStartsFreshDryRunAndInvalidatesOldCq() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID preflightId = UUID.randomUUID();
        UUID oldDryRunId = UUID.randomUUID();
        UUID newDryRunId = UUID.randomUUID();
        String sourceBranch = "feat/task";
        String headCommit = "0123456789012345678901234567890123456789";
        String targetCommit = "abcdef0123456789abcdef0123456789abcdef01";

        MrPreflightRequestEntity request = new MrPreflightRequestEntity();
        request.setId(preflightId);
        request.setProjectId(projectId);
        request.setTriggerTaskId(taskId);
        request.setProjectRepositoryId(repositoryId);
        request.setSourceBranch(sourceBranch);
        request.setTargetBranch("main");
        request.setHeadCommit(headCommit);
        request.setTargetCommit(targetCommit);
        request.setDryRunId(oldDryRunId);
        request.setStatus("CQ_REJECTED");
        when(preflightRequests.selectByIdForUpdate(preflightId)).thenReturn(request);
        when(preflightRequests.selectById(preflightId)).thenReturn(request);
        when(testRuns.retryPreflightDryRun(projectId, oldDryRunId, userId))
                .thenReturn(new DryRunResponse(newDryRunId.toString(), null, null, headCommit, "main", targetCommit,
                        "QUEUED", null, null, null));
        when(preflightRequests.updateById(any(MrPreflightRequestEntity.class))).thenReturn(1);
        DryRunEntity queuedDryRun = new DryRunEntity();
        queuedDryRun.setStatus("QUEUED");
        when(dryRuns.selectById(newDryRunId)).thenReturn(queuedDryRun);
        MrPreflightTaskEntity link = new MrPreflightTaskEntity();
        link.setPreflightId(preflightId);
        link.setTaskId(taskId);
        link.setRole("TRIGGER");
        when(preflightTasks.selectByPreflight(preflightId)).thenReturn(List.of(link));
        when(diffs.selectDeliveredDiffIds(projectId, repositoryId, List.of(taskId))).thenReturn(List.of());
        when(mergeRequests.selectOne(any())).thenReturn(null);

        MergeRequestPreflightResponse response = service.retryPreflight(projectId, preflightId, userId);

        assertEquals("DRY_RUN_QUEUED", response.getStatus());
        assertEquals(newDryRunId.toString(), response.getDryRunId());
        verify(testRuns).retryPreflightDryRun(projectId, oldDryRunId, userId);
    }

    @Test
    void retryPreflightRejectedWhenNotInCqRejectedOrFailedState() {
        UUID projectId = UUID.randomUUID();
        UUID preflightId = UUID.randomUUID();
        MrPreflightRequestEntity request = new MrPreflightRequestEntity();
        request.setId(preflightId);
        request.setProjectId(projectId);
        request.setStatus("WAITING_CQ");
        request.setDryRunId(UUID.randomUUID());
        when(preflightRequests.selectByIdForUpdate(preflightId)).thenReturn(request);

        assertThrows(ApiException.class, () -> service.retryPreflight(projectId, preflightId, UUID.randomUUID()));
        verify(testRuns, never()).retryPreflightDryRun(any(), any(), any());
    }

    @Test
    void requestPreflightRejectedWhenBranchHasOpenMr() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String sourceBranch = "feat/task";

        TaskEntity task = task(projectId, taskId, workspaceId, userId);
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, sourceBranch,
                "0123456789012345678901234567890123456789", "main");
        ProjectRepositoryEntity repository = repository(projectId, repositoryId, "main");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(repositories.selectById(repositoryId)).thenReturn(repository);
        MergeRequestEntity open = new MergeRequestEntity();
        open.setId(UUID.randomUUID());
        open.setStatus("OPEN");
        when(mergeRequests.selectOne(any())).thenReturn(open);

        assertThrows(ApiException.class,
                () -> service.requestPreflight(projectId, userId, taskId, repositoryId, null));
        verify(testRuns, never()).createAutomaticDryRun(any(), any(), any(), any());
    }

    private void runTransactionCallback() {
        doAnswer(invocation -> {
            java.util.function.Consumer<?> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    private TaskEntity task(UUID projectId, UUID taskId, UUID workspaceId, UUID userId) {
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setWorkspaceId(workspaceId);
        task.setDeliveryMode("DIFF_FIRST");
        task.setStatus("SUCCEEDED");
        task.setCreatedBy(userId);
        return task;
    }

    private WorkspaceRepositoryEntity worktree(UUID workspaceId, UUID repositoryId, String sourceBranch,
                                               String headCommit, String baseRef) {
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId);
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setSourceBranch(sourceBranch);
        worktree.setHeadCommit(headCommit);
        worktree.setBaseRef(baseRef);
        return worktree;
    }

    private ProjectRepositoryEntity repository(UUID projectId, UUID repositoryId, String defaultBranch) {
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setDefaultBranch(defaultBranch);
        return repository;
    }

    private DryRunResponse dryRunResponse(UUID dryRunId, String targetCommit) {
        return new DryRunResponse(dryRunId.toString(), null, null, null, "main", targetCommit,
                "QUEUED", null, null, null);
    }
}
