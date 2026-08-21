package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.dto.MergeRequestCreateRequest;
import qg.qgent.dto.PreflightGateResponse;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.MrPreflightRequestMapper;
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
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;

class MrFirstAutomationServiceTest {
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceRepositoryMapper worktrees = mock(WorkspaceRepositoryMapper.class);
    private final ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
    private final DryRunMapper dryRuns = mock(DryRunMapper.class);
    private final MergeRequestMapper mergeRequests = mock(MergeRequestMapper.class);
    private final MrPreflightRequestMapper preflightRequests = mock(MrPreflightRequestMapper.class);
    private final MergeRequestService mrService = mock(MergeRequestService.class);
    private final PreflightGateService preflightGates = mock(PreflightGateService.class);
    private final MrPreflightService preflightService = mock(MrPreflightService.class);
    private final MrFirstAutomationService service = new MrFirstAutomationService(tasks, worktrees, repositories,
            dryRuns, mergeRequests, preflightRequests, mrService, preflightGates, preflightService);

    @Test
    void preflightRequestedStartsOneBranchLevelPreflightPerWorkspaceRepository() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId, workspaceId);
        task.setCreatedBy(creator);
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, "main");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));

        service.onPreflightRequested(new MrFirstPreflightRequestedDomainEvent(projectId, taskId));

        verify(preflightService).requestPreflight(projectId, creator, taskId, repositoryId, null);
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
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, "develop");
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(repositories.selectById(repositoryId)).thenReturn(new ProjectRepositoryEntity());
        when(preflightGates.get(projectId, taskId, repositoryId, "develop", creator))
                .thenReturn(new PreflightGateResponse(null, null, null, null, null, "PASSED", List.of(), null, null));

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
        UUID creator = UUID.randomUUID();
        TaskEntity waiting = task(projectId, taskId, workspaceId);
        waiting.setCreatedBy(creator);
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, "main");
        when(tasks.selectList(any())).thenReturn(List.of(waiting));
        when(tasks.selectById(taskId)).thenReturn(waiting);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(preflightRequests.selectRecoverable(50)).thenReturn(List.of());
        // 恢复调度只领取 PASSED 的 Dry Run 创建 MR；未通过的不进入 MR 创建分支。
        when(dryRuns.selectList(any())).thenReturn(List.of());

        service.recover();

        verify(preflightService).requestPreflight(projectId, creator, taskId, repositoryId, null);
        verify(mrService, never()).create(any(), any(), any());
    }

    @Test
    void noChangesFailureStopsRetryingAndMarksTaskFailed() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        UUID creator = UUID.randomUUID();
        TaskEntity waiting = task(projectId, taskId, workspaceId);
        waiting.setCreatedBy(creator);
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, "main");
        when(tasks.selectById(taskId)).thenReturn(waiting);
        when(worktrees.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        doThrow(new ApiException(HttpStatus.CONFLICT, "MR_NO_CHANGES",
                "源分支与目标分支当前提交相同，没有可创建 MR 的变更"))
                .when(preflightService).requestPreflight(projectId, creator, taskId, repositoryId, null);

        service.onPreflightRequested(new MrFirstPreflightRequestedDomainEvent(projectId, taskId));

        verify(tasks).failMrPreflightNoChanges(projectId, taskId);
        verify(preflightService).requestPreflight(projectId, creator, taskId, repositoryId, null);
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
