package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import qg.qgent.dto.DryRunCreateRequest;
import qg.qgent.dto.TestRunCreateRequest;
import qg.qgent.dto.TestRunResponse;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TestsetEntity;
import qg.qgent.entity.TestRunEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.*;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitResolveResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class TestRunServiceTest {
    private final TestRunMapper testRuns = mock(TestRunMapper.class);
    private final DryRunMapper dryRuns = mock(DryRunMapper.class);
    private final ProjectRepositoryMapper repositories = mock(ProjectRepositoryMapper.class);
    private final TestsetMapper testsets = mock(TestsetMapper.class);
    private final RepositoryBranchConfigMapper branches = mock(RepositoryBranchConfigMapper.class);
    private final RepositoryBranchConfigTestsetMapper required = mock(RepositoryBranchConfigTestsetMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService events = mock(EventService.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceRepositoryMapper workspaces = mock(WorkspaceRepositoryMapper.class);
    private final TestRunExecutionDispatcher executions = mock(TestRunExecutionDispatcher.class);
    private final SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
    private final TestRunService service = new TestRunService(testRuns, dryRuns, repositories, testsets, branches,
            required, access, events, tasks, workspaces, executions, worker);

    @Test
    void taskRunIsQueuedThenDispatchedWithValidatedWorkspace() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID(), testsetId = UUID.randomUUID();
        repository(projectId, repositoryId);
        TestsetEntity testset = new TestsetEntity();
        testset.setId(testsetId); testset.setProjectId(projectId); testset.setProjectRepositoryId(repositoryId);
        testset.setStatus("ENABLED"); testset.setDefinition(Map.of("command", "mvn test", "timeoutSeconds", 60,
                "passRule", Map.of("type", "EXIT_CODE", "expected", 0)));
        when(testsets.selectById(testsetId)).thenReturn(testset);
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        when(tasks.selectById(taskId)).thenReturn(task);
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId); worktree.setProjectRepositoryId(repositoryId);
        worktree.setBaseCommit("0123456789012345678901234567890123456789");
        when(workspaces.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        TestRunCreateRequest request = new TestRunCreateRequest();
        request.setRepositoryId(repositoryId); request.setTaskId(taskId); request.setTestsetIds(List.of(testsetId));

        TestRunResponse response = service.createTestRun(projectId, actor, request);

        assertEquals("QUEUED", response.getStatus());
        verify(worker).createTestSnapshot(eq(workspaceId), eq(repositoryId), any(UUID.class), eq(projectId));
        verify(executions).dispatchTestRun(any(UUID.class));
        InOrder order = inOrder(testRuns, worker);
        order.verify(testRuns).insert(any(TestRunEntity.class));
        order.verify(worker).createTestSnapshot(eq(workspaceId), eq(repositoryId), any(UUID.class), eq(projectId));
    }

    @Test
    void snapshotFailureLeavesTerminalRecordForJanitor() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID(), testsetId = UUID.randomUUID();
        repository(projectId, repositoryId);
        TestsetEntity testset = new TestsetEntity();
        testset.setId(testsetId); testset.setProjectId(projectId); testset.setProjectRepositoryId(repositoryId);
        testset.setStatus("ENABLED"); testset.setDefinition(Map.of("command", "mvn test", "timeoutSeconds", 60,
                "passRule", Map.of("type", "EXIT_CODE", "expected", 0)));
        when(testsets.selectById(testsetId)).thenReturn(testset);
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        when(tasks.selectById(taskId)).thenReturn(task);
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId); worktree.setProjectRepositoryId(repositoryId); worktree.setBaseCommit("base");
        when(workspaces.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        doThrow(new RuntimeException("snapshot failed")).when(worker)
                .createTestSnapshot(eq(workspaceId), eq(repositoryId), any(), eq(projectId));
        doThrow(new RuntimeException("delete failed")).when(worker).deleteWorkspace(any());
        TestRunCreateRequest request = new TestRunCreateRequest();
        request.setRepositoryId(repositoryId); request.setTaskId(taskId); request.setTestsetIds(List.of(testsetId));

        TestRunResponse response = service.createTestRun(projectId, actor, request);

        assertEquals("FAILED", response.getStatus());
        verify(testRuns).insert(any(TestRunEntity.class));
        verify(testRuns).updateById(argThat((TestRunEntity run) -> "FAILED".equals(run.getStatus())
                && run.getExecutionWorkspaceId() != null));
        verify(testRuns, never()).clearExecutionWorkspace(any(), any());
    }

    @Test
    void dryRunDispatchesDocumentedSourceRefAndDoesNotPersistItAsCommit() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        repository(projectId, repositoryId);
        DryRunCreateRequest request = new DryRunCreateRequest();
        request.setRepositoryId(repositoryId); request.setSourceRef("feat/login"); request.setTargetBranch("main");
        WorkerGitResolveResponse source = new WorkerGitResolveResponse();
        source.setCommitSha("0123456789012345678901234567890123456789");
        WorkerGitResolveResponse target = new WorkerGitResolveResponse();
        target.setCommitSha("abcdefabcdefabcdefabcdefabcdefabcdefabcd");
        when(worker.resolveGitRef(any())).thenReturn(source, target);

        service.createDryRun(projectId, actor, request);

        verify(executions).dispatchDryRun(any(UUID.class));
        verify(dryRuns).insert(argThat((DryRunEntity run) ->
                source.getCommitSha().equals(run.getHeadCommit())
                        && target.getCommitSha().equals(run.getResolvedTargetCommit())
                        && "QUEUED".equals(run.getStatus())));
    }

    private void repository(UUID projectId, UUID repositoryId) {
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId); repository.setProjectId(projectId); repository.setDefaultBranch("main");
        when(repositories.selectById(repositoryId)).thenReturn(repository);
    }
}
