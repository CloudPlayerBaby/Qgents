package qg.qgent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import qg.qgent.api.ApiException;
import qg.qgent.dto.DryRunCreateRequest;
import qg.qgent.dto.TestRunCreateRequest;
import qg.qgent.dto.TestRunResponse;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RepositoryBranchConfigEntity;
import qg.qgent.entity.RepositoryBranchConfigTestsetEntity;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private final GitStoreSyncService gitStores = mock(GitStoreSyncService.class);
    private final TestRunService service = new TestRunService(testRuns, dryRuns, repositories, testsets, branches,
            required, access, events, tasks, workspaces, executions, worker, gitStores);

    @BeforeEach
    void normalizeTargetBranchesInTests() {
        when(gitStores.normalizeTargetBranch(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).trim());
    }

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
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId);
        task.setWorkspaceId(workspaceId); task.setStatus("WAITING_DIFF_CONFIRMATION");
        when(tasks.selectById(taskId)).thenReturn(task);
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId); worktree.setProjectRepositoryId(repositoryId);
        worktree.setBaseCommit("0123456789012345678901234567890123456789");
        when(workspaces.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        TestRunCreateRequest request = new TestRunCreateRequest();
        request.setRepositoryId(repositoryId); request.setTaskId(taskId); request.setTestsetIds(List.of(testsetId));

        TestRunResponse response = service.createTestRun(projectId, actor, request);

        assertEquals("QUEUED", response.getStatus());
        verify(worker, never()).createTestSnapshot(any(), any(), any(), any(), any());
        verify(executions).dispatchTestRun(any(UUID.class));
        InOrder order = inOrder(testRuns, executions);
        order.verify(testRuns).insert(any(TestRunEntity.class));
        order.verify(executions).dispatchTestRun(any(UUID.class));
    }

    @Test
    void snapshotPreparationIsDeferredToTheExecutor() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID(), testsetId = UUID.randomUUID();
        repository(projectId, repositoryId);
        TestsetEntity testset = new TestsetEntity();
        testset.setId(testsetId); testset.setProjectId(projectId); testset.setProjectRepositoryId(repositoryId);
        testset.setStatus("ENABLED"); testset.setDefinition(Map.of("command", "mvn test", "timeoutSeconds", 60,
                "passRule", Map.of("type", "EXIT_CODE", "expected", 0)));
        when(testsets.selectById(testsetId)).thenReturn(testset);
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId);
        task.setWorkspaceId(workspaceId); task.setStatus("WAITING_DIFF_CONFIRMATION");
        when(tasks.selectById(taskId)).thenReturn(task);
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId); worktree.setProjectRepositoryId(repositoryId); worktree.setBaseCommit("base");
        when(workspaces.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        TestRunCreateRequest request = new TestRunCreateRequest();
        request.setRepositoryId(repositoryId); request.setTaskId(taskId); request.setTestsetIds(List.of(testsetId));

        TestRunResponse response = service.createTestRun(projectId, actor, request);

        assertEquals("QUEUED", response.getStatus());
        verify(testRuns).insert(any(TestRunEntity.class));
        verify(testRuns, never()).updateById(any(TestRunEntity.class));
        verify(worker, never()).createTestSnapshot(any(), any(), any(), any(), any());
        verify(executions).dispatchTestRun(any(UUID.class));
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
        String targetCommit = "abcdefabcdefabcdefabcdefabcdefabcdefabcd";
        when(worker.resolveGitRef(any())).thenReturn(source);
        when(gitStores.refreshTargetBranch(eq(projectId), any(ProjectRepositoryEntity.class), eq("main")))
                .thenReturn(targetCommit);

        service.createDryRun(projectId, actor, request);

        verify(executions).dispatchDryRun(any(UUID.class));
        verify(dryRuns).insert(argThat((DryRunEntity run) ->
                source.getCommitSha().equals(run.getHeadCommit())
                        && targetCommit.equals(run.getResolvedTargetCommit())
                        && "QUEUED".equals(run.getStatus())));
        verify(gitStores).refreshTargetBranch(eq(projectId), any(ProjectRepositoryEntity.class), eq("main"));
    }

    @Test
    void dryRunNormalizesTargetBranchBeforeLoadingMandatoryTestsets() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID testsetId = UUID.randomUUID();
        repository(projectId, repositoryId);
        TestsetEntity testset = new TestsetEntity();
        testset.setId(testsetId);
        testset.setProjectId(projectId);
        testset.setProjectRepositoryId(repositoryId);
        testset.setStatus("ENABLED");
        testset.setDefinition(Map.of("command", "mvn test", "timeoutSeconds", 60,
                "passRule", Map.of("type", "EXIT_CODE", "expected", 0)));
        RepositoryBranchConfigEntity config = new RepositoryBranchConfigEntity();
        config.setId(UUID.randomUUID());
        RepositoryBranchConfigTestsetEntity relation = new RepositoryBranchConfigTestsetEntity();
        relation.setTestsetId(testsetId);
        when(branches.selectOne(any())).thenReturn(config);
        when(required.selectByBranchConfigId(config.getId())).thenReturn(List.of(relation));
        when(testsets.selectById(testsetId)).thenReturn(testset);
        when(gitStores.normalizeTargetBranch(" main ")).thenReturn("main");
        when(gitStores.refreshTargetBranch(eq(projectId), any(ProjectRepositoryEntity.class), eq("main")))
                .thenReturn("abcdefabcdefabcdefabcdefabcdefabcdefabcd");
        WorkerGitResolveResponse source = new WorkerGitResolveResponse();
        source.setCommitSha("0123456789012345678901234567890123456789");
        when(worker.resolveGitRef(any())).thenReturn(source);

        DryRunCreateRequest request = new DryRunCreateRequest();
        request.setRepositoryId(repositoryId);
        request.setSourceRef("feat/login");
        request.setTargetBranch(" main ");

        service.createDryRun(projectId, actor, request);

        verify(required).selectByBranchConfigId(config.getId());
        verify(dryRuns).insert(argThat((DryRunEntity run) -> "main".equals(run.getTargetBranch())
                && run.getTestsetSnapshot().size() == 1));
    }

    @Test
    void taskDryRunRejectsAnOutdatedRemoteSourceBranch() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        repository(projectId, repositoryId);
        TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        task.setStatus("WAITING_PREFLIGHT");
        when(tasks.selectById(taskId)).thenReturn(task);
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId); worktree.setProjectRepositoryId(repositoryId);
        worktree.setSourceBranch("feat/login");
        worktree.setHeadCommit("1111111111111111111111111111111111111111");
        when(workspaces.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(gitStores.refreshTargetBranch(eq(projectId), any(ProjectRepositoryEntity.class), eq("main")))
                .thenReturn("2222222222222222222222222222222222222222");
        WorkerGitResolveResponse source = new WorkerGitResolveResponse();
        source.setCommitSha("3333333333333333333333333333333333333333");
        when(worker.resolveGitRef(any())).thenReturn(source);
        DryRunCreateRequest request = new DryRunCreateRequest();
        request.setRepositoryId(repositoryId); request.setTaskId(taskId);
        request.setSourceRef("feat/login"); request.setTargetBranch("main");

        ApiException failure = assertThrows(ApiException.class, () -> service.createDryRun(projectId, actor, request));

        assertEquals("DRY_RUN_TASK_HEAD_NOT_PUSHED", failure.code());
        verify(dryRuns, never()).insert(any(DryRunEntity.class));
        verify(executions, never()).dispatchDryRun(any());
    }

    @Test
    void normalTestRunDoesNotInheritTheDefaultBranchMandatoryTestsets() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID selectedId = UUID.randomUUID(), mandatoryId = UUID.randomUUID();
        repository(projectId, repositoryId);
        TestsetEntity selected = new TestsetEntity();
        selected.setId(selectedId); selected.setProjectId(projectId); selected.setProjectRepositoryId(repositoryId);
        selected.setStatus("ENABLED"); selected.setDefinition(Map.of("command", "mvn test", "timeoutSeconds", 60,
                "passRule", Map.of("type", "EXIT_CODE", "expected", 0)));
        when(testsets.selectById(selectedId)).thenReturn(selected);
        RepositoryBranchConfigEntity config = new RepositoryBranchConfigEntity(); config.setId(UUID.randomUUID());
        when(branches.selectOne(any())).thenReturn(config);
        RepositoryBranchConfigTestsetEntity relation = new RepositoryBranchConfigTestsetEntity();
        relation.setTestsetId(mandatoryId);
        when(required.selectByBranchConfigId(config.getId())).thenReturn(List.of(relation));
        TestRunCreateRequest request = new TestRunCreateRequest();
        request.setRepositoryId(repositoryId); request.setRef("feature/only-selected"); request.setTestsetIds(List.of(selectedId));

        TestRunResponse response = service.createTestRun(projectId, actor, request);

        assertEquals("QUEUED", response.getStatus());
        verify(testsets, never()).selectById(mandatoryId);
        verify(worker, never()).resolveGitRef(any());
        verify(executions).dispatchTestRun(any(UUID.class));
    }

    @Test
    void taskScopedTestRunRejectsAWorkspaceThatAnAgentMayStillModify() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID(), testsetId = UUID.randomUUID();
        repository(projectId, repositoryId);
        TestsetEntity testset = new TestsetEntity();
        testset.setId(testsetId); testset.setProjectId(projectId); testset.setProjectRepositoryId(repositoryId);
        testset.setStatus("ENABLED"); testset.setDefinition(Map.of("command", "mvn test", "timeoutSeconds", 60,
                "passRule", Map.of("type", "EXIT_CODE", "expected", 0)));
        when(testsets.selectById(testsetId)).thenReturn(testset);
        TaskEntity task = new TaskEntity(); task.setId(taskId); task.setProjectId(projectId);
        task.setWorkspaceId(UUID.randomUUID()); task.setStatus("RUNNING");
        when(tasks.selectById(taskId)).thenReturn(task);
        TestRunCreateRequest request = new TestRunCreateRequest();
        request.setRepositoryId(repositoryId); request.setTaskId(taskId); request.setTestsetIds(List.of(testsetId));

        ApiException failure = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> service.createTestRun(projectId, actor, request));

        assertEquals("TEST_RUN_TASK_WORKSPACE_UNSTABLE", failure.code());
        verify(testRuns, never()).insert(any(TestRunEntity.class));
    }

    private void repository(UUID projectId, UUID repositoryId) {
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId); repository.setProjectId(projectId); repository.setDefaultBranch("main");
        when(repositories.selectById(repositoryId)).thenReturn(repository);
    }
}
