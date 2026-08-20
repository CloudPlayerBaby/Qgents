package qg.qgent.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import qg.qgent.api.ApiException;
import qg.qgent.dto.DryRunCreateRequest;
import qg.qgent.dto.DryRunResponse;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.DryRunListItemResponse;
import qg.qgent.dto.TestRunCreateRequest;
import qg.qgent.dto.TestRunListItemResponse;
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
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    @BeforeAll
    static void initTableInfo() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.getTypeHandlerRegistry().register(UuidBinaryTypeHandler.class);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, TestRunEntity.class);
        TableInfoHelper.initTableInfo(assistant, DryRunEntity.class);
    }

    @BeforeEach
    void normalizeTargetBranchesInTests() {
        when(gitStores.normalizeTargetBranch(anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).trim());
    }

    @Test
    void listsTestRunsWithLightweightFieldsAndStableCursor() {
        UUID projectId = UUID.randomUUID();
        TestRunEntity first = new TestRunEntity();
        first.setId(UUID.randomUUID());
        first.setProjectId(projectId);
        first.setProjectRepositoryId(UUID.randomUUID());
        first.setTestsetIds(List.of(UUID.randomUUID().toString()));
        first.setStatus("RUNNING");
        first.setCreatedAt(LocalDateTime.of(2026, 8, 19, 8, 0));
        first.setStartedAt(LocalDateTime.of(2026, 8, 19, 8, 0, 1));
        when(testRuns.selectList(any())).thenReturn(List.of(first));

        ApiPageResponse<TestRunListItemResponse> response = service.listTestRuns(
                projectId, UUID.randomUUID(), null, null, "QUEUED,RUNNING", null, null, 20, "req-1");

        assertEquals("req-1", response.requestId());
        assertEquals(1, response.data().size());
        assertEquals("RUNNING", response.data().getFirst().getStatus());
        assertEquals(first.getId().toString(), response.data().getFirst().getId());
        assertEquals(true, response.data().getFirst().getDurationMs() >= 0);
        assertEquals(null, response.page().getNextCursor());
        assertEquals(false, response.page().getHasMore());
    }

    @Test
    void derivesTestRunDurationOnTheServerAndNeverReturnsNegativeDuration() {
        UUID projectId = UUID.randomUUID(), runId = UUID.randomUUID();
        TestRunEntity run = new TestRunEntity();
        run.setId(runId); run.setProjectId(projectId); run.setProjectRepositoryId(UUID.randomUUID());
        run.setStatus("PASSED");
        run.setStartedAt(LocalDateTime.of(2026, 8, 19, 8, 0));
        run.setFinishedAt(LocalDateTime.of(2026, 8, 19, 8, 0, 5));
        when(testRuns.selectById(runId)).thenReturn(run);

        TestRunResponse response = service.testRun(projectId, runId, UUID.randomUUID());

        assertEquals(5_000L, response.getDurationMs());

        run.setFinishedAt(LocalDateTime.of(2026, 8, 19, 7, 59, 59));
        assertEquals(null, service.testRun(projectId, runId, UUID.randomUUID()).getDurationMs());
    }

    @Test
    void listsDryRunsAndRejectsUnknownStatus() {
        UUID projectId = UUID.randomUUID();
        DryRunEntity run = new DryRunEntity();
        run.setId(UUID.randomUUID());
        run.setProjectId(projectId);
        run.setProjectRepositoryId(UUID.randomUUID());
        run.setSourceRef("feat/login");
        run.setTargetBranch("main");
        run.setStatus("PASSED");
        run.setCreatedAt(LocalDateTime.of(2026, 8, 19, 8, 0));
        when(dryRuns.selectList(any())).thenReturn(List.of(run));

        ApiPageResponse<DryRunListItemResponse> response = service.listDryRuns(
                projectId, UUID.randomUUID(), null, null, "PASSED", "main", null, null, 20, "req-2");
        assertEquals(1, response.data().size());
        assertEquals("feat/login", response.data().getFirst().getSourceRef());

        ApiException error = assertThrows(ApiException.class, () -> service.listDryRuns(
                projectId, UUID.randomUUID(), null, null, "NOT_A_STATUS", null, null, null, 20, "req-3"));
        assertEquals("INVALID_STATUS_FILTER", error.code());
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
    void retryCreatesNewImmutableAttemptForTransientWorkerFailure() {
        UUID projectId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        DryRunEntity source = new DryRunEntity();
        source.setId(UUID.randomUUID());
        source.setProjectId(projectId);
        source.setProjectRepositoryId(UUID.randomUUID());
        source.setSourceRef("feat/login");
        source.setHeadCommit("head");
        source.setResolvedTargetCommit("target");
        source.setTargetBranch("main");
        source.setStatus("FAILED");
        source.setAttemptCount(1);
        source.setTestsetSnapshot(List.of());
        source.setReport(Map.of("failureCode", "SANDBOX_WORKER_UNAVAILABLE"));
        repository(projectId, source.getProjectRepositoryId());
        when(dryRuns.selectByIdForUpdate(source.getId())).thenReturn(source);
        when(dryRuns.selectCount(any())).thenReturn(0L);

        DryRunResponse retry = service.retryDryRun(projectId, source.getId(), actor);

        assertEquals("QUEUED", retry.getStatus());
        verify(dryRuns).insert(argThat((DryRunEntity value) -> source.getId().equals(value.getRetryOfDryRunId())
                && "SANDBOX_WORKER_UNAVAILABLE".equals(value.getRetryReasonCode())
                && "head".equals(value.getHeadCommit())
                && "target".equals(value.getResolvedTargetCommit())));
        verify(executions).dispatchDryRun(any(UUID.class));
    }

    @Test
    void rejectsUnboundRepositoryBeforeCreatingManualTestOrDryRun() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        unboundRepository(projectId, repositoryId);
        TestRunCreateRequest testRequest = new TestRunCreateRequest();
        testRequest.setRepositoryId(repositoryId); testRequest.setRef("feature/test"); testRequest.setTestsetIds(List.of());
        ApiException testFailure = assertThrows(ApiException.class,
                () -> service.createTestRun(projectId, actor, testRequest));
        assertEquals("PROJECT_REPOSITORY_UNBOUND", testFailure.code());

        DryRunCreateRequest dryRequest = new DryRunCreateRequest();
        dryRequest.setRepositoryId(repositoryId); dryRequest.setSourceRef("feature/test"); dryRequest.setTargetBranch("main");
        ApiException dryFailure = assertThrows(ApiException.class,
                () -> service.createDryRun(projectId, actor, dryRequest));
        assertEquals("PROJECT_REPOSITORY_UNBOUND", dryFailure.code());
        verify(testRuns, never()).insert(any(TestRunEntity.class));
        verify(dryRuns, never()).insert(any(DryRunEntity.class));
        verifyNoInteractions(worker, executions);
    }

    @Test
    void rejectsUnboundRepositoryForAutomaticAndRetryDryRuns() {
        UUID projectId = UUID.randomUUID(), repositoryId = UUID.randomUUID(), taskId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        TaskEntity task = task(projectId, taskId, workspaceId);
        when(tasks.selectById(taskId)).thenReturn(task);
        when(workspaces.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree(workspaceId, repositoryId, "feature/test", "head")));
        unboundRepository(projectId, repositoryId);
        ApiException automaticFailure = assertThrows(ApiException.class,
                () -> service.createAutomaticDryRun(projectId, taskId, repositoryId, "main"));
        assertEquals("PROJECT_REPOSITORY_UNBOUND", automaticFailure.code());

        DryRunEntity failed = new DryRunEntity();
        failed.setId(UUID.randomUUID()); failed.setProjectId(projectId); failed.setProjectRepositoryId(repositoryId);
        failed.setStatus("FAILED"); failed.setReport(Map.of("failureCode", "SANDBOX_WORKER_UNAVAILABLE"));
        when(dryRuns.selectByIdForUpdate(failed.getId())).thenReturn(failed);
        ApiException retryFailure = assertThrows(ApiException.class,
                () -> service.retryDryRun(projectId, failed.getId(), UUID.randomUUID()));
        assertEquals("PROJECT_REPOSITORY_UNBOUND", retryFailure.code());
        verify(dryRuns, never()).insert(any(DryRunEntity.class));
        verifyNoInteractions(worker, executions);
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

    @Test
    void automaticDryRunReusesDeterministicConflictWithoutRecreating() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String oldHead = "1111111111111111111111111111111111111111";
        String targetCommit = "2222222222222222222222222222222222222222";
        repository(projectId, repositoryId);
        TaskEntity task = task(projectId, taskId, workspaceId);
        when(tasks.selectById(taskId)).thenReturn(task);
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, "feat/login", oldHead);
        when(workspaces.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(gitStores.refreshTargetBranch(eq(projectId), any(ProjectRepositoryEntity.class), eq("main")))
                .thenReturn(targetCommit);
        DryRunEntity conflict = conflictDryRun(projectId, taskId, repositoryId, oldHead, targetCommit);
        when(dryRuns.selectOne(any())).thenReturn(conflict);
        // 远端 head 未推进：冲突未解决，无条件复用旧 FAILED，不再新建。
        when(gitStores.refreshSourceHead(eq(projectId), any(), any(), eq(workspaceId))).thenReturn(null);

        DryRunResponse response = service.createAutomaticDryRun(projectId, taskId, repositoryId, "main");

        assertEquals("FAILED", response.getStatus());
        verify(dryRuns, never()).insert(any(DryRunEntity.class));
        verify(gitStores).refreshSourceHead(eq(projectId), any(), any(), eq(workspaceId));
    }

    @Test
    void automaticDryRunCreatesFreshRunWhenConflictHeadAdvancedRemotely() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String oldHead = "1111111111111111111111111111111111111111";
        String newHead = "3333333333333333333333333333333333333333";
        String targetCommit = "2222222222222222222222222222222222222222";
        repository(projectId, repositoryId);
        TaskEntity task = task(projectId, taskId, workspaceId);
        when(tasks.selectById(taskId)).thenReturn(task);
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, "feat/login", oldHead);
        when(workspaces.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(gitStores.refreshTargetBranch(eq(projectId), any(ProjectRepositoryEntity.class), eq("main")))
                .thenReturn(targetCommit);
        DryRunEntity conflict = conflictDryRun(projectId, taskId, repositoryId, oldHead, targetCommit);
        WorkerGitResolveResponse source = new WorkerGitResolveResponse();
        source.setCommitSha(newHead);
        when(worker.resolveGitRef(any())).thenReturn(source);
        // 第一次查询返回旧 head 的冲突，刷新后第二次查询新 head 无记录 → 为推进后的 head 新建 dry-run。
        when(dryRuns.selectOne(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenAnswer(new org.mockito.stubbing.Answer<>() {
                    private int calls;
                    @Override
                    public DryRunEntity answer(org.mockito.invocation.InvocationOnMock ignored) {
                        return calls++ == 0 ? conflict : null;
                    }
                });
        when(gitStores.refreshSourceHead(eq(projectId), any(), any(), eq(workspaceId))).thenReturn(newHead);

        DryRunResponse response = service.createAutomaticDryRun(projectId, taskId, repositoryId, "main");

        assertEquals("QUEUED", response.getStatus());
        verify(dryRuns).insert(org.mockito.ArgumentMatchers.<DryRunEntity>argThat(
                run -> newHead.equalsIgnoreCase(run.getHeadCommit())));
    }

    @Test
    void automaticDryRunStillRetriesTransientFailuresAfterWindow() {
        UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        String head = "1111111111111111111111111111111111111111";
        String targetCommit = "2222222222222222222222222222222222222222";
        repository(projectId, repositoryId);
        TaskEntity task = task(projectId, taskId, workspaceId);
        when(tasks.selectById(taskId)).thenReturn(task);
        WorkspaceRepositoryEntity worktree = worktree(workspaceId, repositoryId, "feat/login", head);
        when(workspaces.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(gitStores.refreshTargetBranch(eq(projectId), any(ProjectRepositoryEntity.class), eq("main")))
                .thenReturn(targetCommit);
        DryRunEntity transientFailure = conflictDryRun(projectId, taskId, repositoryId, head, targetCommit);
        transientFailure.setReport(Map.of("failureCode", "SANDBOX_WORKER_UNAVAILABLE", "message", "worker unavailable"));
        transientFailure.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(dryRuns.selectOne(any())).thenReturn(transientFailure);
        WorkerGitResolveResponse source = new WorkerGitResolveResponse();
        source.setCommitSha(head);
        when(worker.resolveGitRef(any())).thenReturn(source);

        DryRunResponse response = service.createAutomaticDryRun(projectId, taskId, repositoryId, "main");

        assertEquals("QUEUED", response.getStatus());
        verify(dryRuns).insert(any(DryRunEntity.class));
        // 瞬时失败不是确定性冲突，不触发 source 分支 head 刷新。
        verify(gitStores, never()).refreshSourceHead(any(), any(), any(), any());
    }

    private TaskEntity task(UUID projectId, UUID taskId, UUID workspaceId) {
        TaskEntity task = new TaskEntity();
        task.setId(taskId); task.setProjectId(projectId); task.setWorkspaceId(workspaceId);
        task.setStatus("WAITING_PREFLIGHT"); task.setDeliveryMode("MR_FIRST");
        task.setCreatedBy(UUID.randomUUID());
        return task;
    }

    private WorkspaceRepositoryEntity worktree(UUID workspaceId, UUID repositoryId, String sourceBranch, String head) {
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId); worktree.setProjectRepositoryId(repositoryId);
        worktree.setSourceBranch(sourceBranch); worktree.setHeadCommit(head);
        worktree.setBaseRef("main");
        return worktree;
    }

    private DryRunEntity conflictDryRun(UUID projectId, UUID taskId, UUID repositoryId, String head, String target) {
        DryRunEntity run = new DryRunEntity();
        run.setId(UUID.randomUUID()); run.setProjectId(projectId); run.setTaskId(taskId);
        run.setProjectRepositoryId(repositoryId); run.setHeadCommit(head); run.setResolvedTargetCommit(target);
        run.setTargetBranch("main"); run.setStatus("FAILED");
        run.setReport(Map.of("mergeable", false, "conflicts", List.of("src/a.java"),
                "tests", Map.of("status", "SKIPPED", "reason", "MERGE_CONFLICT")));
        run.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        run.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        return run;
    }

    private void repository(UUID projectId, UUID repositoryId) {
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId); repository.setProjectId(projectId); repository.setDefaultBranch("main");
        repository.setStatus("ACTIVE");
        when(repositories.selectById(repositoryId)).thenReturn(repository);
    }

    private void unboundRepository(UUID projectId, UUID repositoryId) {
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(repositoryId); repository.setProjectId(projectId); repository.setStatus("UNBOUND");
        when(repositories.selectById(repositoryId)).thenReturn(repository);
    }
}
