package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.dto.DryRunResponse;
import qg.qgent.dto.MergeRequestCreateRequest;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.service.event.MrFirstPreflightRequestedDomainEvent;
import qg.qgent.service.event.PreflightCqApprovedDomainEvent;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MrFirstAutomationServiceTest {
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
    private final ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
    private final DryRunMapper dryRuns = mock(DryRunMapper.class);
    private final MergeRequestMapper mergeRequests = mock(MergeRequestMapper.class);
    private final TestRunService testRuns = mock(TestRunService.class);
    private final MergeRequestService mrService = mock(MergeRequestService.class);
    private final MrFirstAutomationService service = new MrFirstAutomationService(tasks, worktrees, repositories,
            dryRuns, mergeRequests, testRuns, mrService);

    @Test
    void preflightRequestedStartsOneDryRunPerWorkspaceRepository() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId, workspaceId);
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, "main");
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setDefaultBranch("develop");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(repositories.selectById(repositoryId)).thenReturn(repository);

        service.onPreflightRequested(new MrFirstPreflightRequestedDomainEvent(projectId, taskId));

        verify(testRuns).createAutomaticDryRun(projectId, taskId, repositoryId, "main");
    }

    @Test
    void approvedPreflightCreatesMrAsAnIdempotentInternalAction() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID dryRunId = UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId, workspaceId);
        task.setCreatedBy(creator);
        task.setTitle("自动创建 MR");
        DryRunEntity dryRun = new DryRunEntity();
        dryRun.setId(dryRunId);
        dryRun.setProjectId(projectId);
        dryRun.setTaskId(taskId);
        dryRun.setProjectRepositoryId(repositoryId);
        dryRun.setTargetBranch("develop");
        dryRun.setStatus("PASSED");
        when(dryRuns.selectById(dryRunId)).thenReturn(dryRun);
        when(tasks.selectById(taskId)).thenReturn(task);

        service.onCqApproved(new PreflightCqApprovedDomainEvent(projectId, dryRunId));

        verify(mrService).create(eq(projectId), eq(creator), any(MergeRequestCreateRequest.class));
    }

    @Test
    void failedDryRunDoesNotCreateMergeRequest() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID dryRunId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId, workspaceId);
        task.setCreatedBy(UUID.randomUUID());
        when(tasks.selectById(taskId)).thenReturn(task);
        DryRunEntity dryRun = new DryRunEntity();
        dryRun.setId(dryRunId);
        dryRun.setProjectId(projectId);
        dryRun.setTaskId(taskId);
        dryRun.setStatus("FAILED");
        when(dryRuns.selectById(dryRunId)).thenReturn(dryRun);

        service.onCqApproved(new PreflightCqApprovedDomainEvent(projectId, dryRunId));

        verify(mrService, never()).create(any(), any(), any());
    }

    @Test
    void recoverRetriesWaitingPreflightTasksWithoutCreatingMrForUnpassedDryRuns() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        TaskEntity waiting = task(projectId, taskId, workspaceId);
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, "main");
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId);
        repository.setProjectId(projectId);
        repository.setDefaultBranch("develop");
        when(tasks.selectList(any())).thenReturn(List.of(waiting));
        when(tasks.selectById(taskId)).thenReturn(waiting);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(repositories.selectById(repositoryId)).thenReturn(repository);
        // 恢复调度只领取 PASSED 的 Dry Run 创建 MR；未通过的不进入 MR 创建分支。
        when(dryRuns.selectList(any())).thenReturn(List.of());

        service.recover();

        verify(testRuns).createAutomaticDryRun(projectId, taskId, repositoryId, "main");
        verify(mrService, never()).create(any(), any(), any());
    }

    private TaskEntity task(UUID projectId, UUID taskId, UUID workspaceId) {
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setWorkspaceId(workspaceId);
        task.setDeliveryMode("MR_FIRST");
        task.setStatus("WAITING_PREFLIGHT");
        return task;
    }

    private WorkspaceRepositoryEntity worktree(UUID workspaceId, UUID repositoryId, String baseRef) {
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId);
        worktree.setProjectRepositoryId(repositoryId);
        worktree.setBaseRef(baseRef);
        worktree.setHeadCommit("0123456789012345678901234567890123456789");
        return worktree;
    }
}
