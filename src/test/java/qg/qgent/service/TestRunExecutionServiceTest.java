package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TestRunEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TestRunMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.orchestration.worker.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TestRunExecutionServiceTest {
    private final TestRunMapper testRuns = mock(TestRunMapper.class);
    private final DryRunMapper dryRuns = mock(DryRunMapper.class);
    private final SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final ProjectRepositoryMapper projectRepositories = mock(ProjectRepositoryMapper.class);
    private final GitStoreSyncService gitStores = mock(GitStoreSyncService.class);
    private final TestRunExecutionService service = new TestRunExecutionService(testRuns, dryRuns, worker,
            mock(EventService.class), tasks, projectRepositories, gitStores);

    @Test
    void testRunSuccessPersistsStructuredResultsWithTestsetFacts() {
        UUID runId = UUID.randomUUID(), testsetId = UUID.randomUUID();
        TestRunEntity run = new TestRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setStatus("RUNNING"); run.setExecutionSourceRef("0123456789012345678901234567890123456789");
        run.setExecutionSnapshot(List.of(Map.of("testsetId", testsetId.toString(), "command", "mvn test",
                "timeoutSeconds", 60, "passRuleType", "EXIT_CODE", "expectedExitCode", 0)));
        when(testRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        when(testRuns.selectById(runId)).thenReturn(run);
        WorkerTestExecutionItemResponse item = new WorkerTestExecutionItemResponse();
        item.setTestsetId(testsetId);
        item.setStatus("PASSED");
        item.setExitCode(0);
        item.setDurationMs(69);
        item.setFailureCode(null);
        WorkerTestExecutionResponse response = new WorkerTestExecutionResponse();
        response.setStatus("PASSED");
        response.setResolvedHeadCommit(run.getExecutionSourceRef());
        response.setResults(List.of(item));
        when(worker.executeTests(any())).thenReturn(response);
        when(testRuns.complete(eq(runId), anyString(), eq("PASSED"), any())).thenReturn(1);

        service.executeTestRun(runId);

        verify(testRuns).complete(eq(runId), anyString(), eq("PASSED"),
                argThat(summary -> "PASSED".equals(summary.get("status"))
                        && run.getExecutionSourceRef().equals(summary.get("resolvedHeadCommit"))
                        && summary.get("results") instanceof List<?> results
                        && results.size() == 1
                        && results.getFirst() instanceof Map<?, ?> row
                        && testsetId.equals(row.get("testsetId"))
                        && "PASSED".equals(row.get("status"))
                        && Integer.valueOf(0).equals(row.get("exitCode"))
                        && Long.valueOf(69L).equals(row.get("durationMs"))
                        && row.get("failureCode") == null));
    }

    @Test
    void testRunFailureKeepsAStableSanitizedFailureCode() {
        UUID runId = UUID.randomUUID();
        TestRunEntity run = new TestRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setExecutionSourceRef("0123456789012345678901234567890123456789"); run.setExecutionSnapshot(List.of());
        when(testRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        when(testRuns.selectById(runId)).thenReturn(run);
        when(worker.executeTests(any())).thenThrow(
                new ApiException(HttpStatus.BAD_GATEWAY, "SANDBOX_WORKER_UNAVAILABLE", "worker down"));
        when(testRuns.complete(eq(runId), anyString(), eq("FAILED"), any())).thenReturn(1);

        service.executeTestRun(runId);

        verify(testRuns).complete(eq(runId), anyString(), eq("FAILED"),
                argThat(summary -> "SANDBOX_WORKER_UNAVAILABLE".equals(summary.get("failureCode"))
                        && summary.get("message") != null));
    }

    @Test
    void onlyAtomicClaimWinnerCallsWorkerAndUsesPersistedSnapshot() {
        UUID runId = UUID.randomUUID(), testsetId = UUID.randomUUID();
        TestRunEntity run = new TestRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setStatus("RUNNING"); run.setExecutionSourceRef("0123456789012345678901234567890123456789");
        run.setExecutionSnapshot(List.of(Map.of("testsetId", testsetId.toString(), "command", "mvn test",
                "timeoutSeconds", 60, "passRuleType", "EXIT_CODE", "expectedExitCode", 0)));
        when(testRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        when(testRuns.selectById(runId)).thenReturn(run);
        WorkerTestExecutionResponse response = new WorkerTestExecutionResponse();
        response.setStatus("PASSED"); response.setResolvedHeadCommit(run.getExecutionSourceRef()); response.setResults(List.of());
        when(worker.executeTests(any())).thenReturn(response);
        when(testRuns.complete(eq(runId), anyString(), eq("PASSED"), any())).thenReturn(1);

        service.executeTestRun(runId);

        verify(worker).executeTests(argThat(request -> request.getWorkspaceId() == null
                && run.getExecutionSourceRef().equals(request.getRef())
                && request.getTestsets().getFirst().getTestsetId().equals(testsetId)));
    }

    @Test
    void taskRunExecutesThePersistedIsolatedWorkspaceSnapshot() {
        UUID runId = UUID.randomUUID(), snapshotWorkspaceId = UUID.randomUUID(), testsetId = UUID.randomUUID();
        TestRunEntity run = new TestRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setTaskId(UUID.randomUUID());
        run.setExecutionWorkspaceId(snapshotWorkspaceId);
        run.setExecutionSourceRef("0123456789012345678901234567890123456789");
        run.setExecutionSnapshot(List.of(Map.of("testsetId", testsetId.toString(), "command", "mvn test",
                "timeoutSeconds", 60, "passRuleType", "EXIT_CODE", "expectedExitCode", 0)));
        when(testRuns.selectById(runId)).thenReturn(run);
        when(testRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        TaskEntity task = new TaskEntity(); task.setId(run.getTaskId()); task.setProjectId(run.getProjectId());
        task.setWorkspaceId(UUID.randomUUID());
        when(tasks.selectById(run.getTaskId())).thenReturn(task);
        WorkerTestExecutionResponse response = new WorkerTestExecutionResponse();
        response.setStatus("PASSED"); response.setResolvedHeadCommit(run.getExecutionSourceRef()); response.setResults(List.of());
        when(worker.executeTests(any())).thenReturn(response);
        when(testRuns.complete(eq(runId), anyString(), eq("PASSED"), any())).thenReturn(1);

        service.executeTestRun(runId);

        verify(worker).executeTests(argThat(request -> snapshotWorkspaceId.equals(request.getWorkspaceId())
                && request.getRef() == null));
        verify(worker).createTestSnapshot(eq(task.getWorkspaceId()), eq(run.getProjectRepositoryId()),
                eq(snapshotWorkspaceId), eq(run.getProjectId()), eq(run.getExecutionSourceRef()));
        verify(worker).deleteWorkspace(snapshotWorkspaceId);
    }

    @Test
    void branchRefIsResolvedInsideTheExecutorBeforeTestsRun() {
        UUID runId = UUID.randomUUID();
        TestRunEntity run = new TestRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setExecutionSourceRef("feature/login"); run.setExecutionSnapshot(List.of());
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity();
        repository.setId(run.getProjectRepositoryId());
        repository.setProjectId(run.getProjectId());
        when(projectRepositories.selectById(run.getProjectRepositoryId())).thenReturn(repository);
        when(gitStores.refreshTargetBranch(run.getProjectId(), repository, "feature/login"))
                .thenReturn("0123456789012345678901234567890123456789");
        when(testRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        when(testRuns.selectById(runId)).thenReturn(run);
        WorkerTestExecutionResponse response = new WorkerTestExecutionResponse();
        response.setStatus("PASSED"); response.setResolvedHeadCommit("0123456789012345678901234567890123456789"); response.setResults(List.of());
        when(worker.executeTests(any())).thenReturn(response);
        when(testRuns.complete(eq(runId), anyString(), eq("PASSED"), any())).thenReturn(1);

        service.executeTestRun(runId);

        verify(gitStores).refreshTargetBranch(run.getProjectId(), repository, "feature/login");
        verify(worker, never()).resolveGitRef(any());
        verify(worker).executeTests(argThat(request ->
                "0123456789012345678901234567890123456789".equals(request.getRef())));
    }

    @Test
    void passedTestForAnotherCommitIsNotRecordedAsPassed() {
        UUID runId = UUID.randomUUID();
        TestRunEntity run = new TestRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setExecutionSourceRef("0123456789012345678901234567890123456789"); run.setExecutionSnapshot(List.of());
        when(testRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        when(testRuns.selectById(runId)).thenReturn(run);
        WorkerTestExecutionResponse response = new WorkerTestExecutionResponse();
        response.setStatus("PASSED"); response.setResolvedHeadCommit("abcdefabcdefabcdefabcdefabcdefabcdefabcd");
        response.setResults(List.of());
        when(worker.executeTests(any())).thenReturn(response);
        when(testRuns.complete(eq(runId), anyString(), eq("FAILED"), any())).thenReturn(1);

        service.executeTestRun(runId);

        verify(testRuns).complete(eq(runId), anyString(), eq("FAILED"),
                argThat(summary -> "TEST_RUN_CONTEXT_MISMATCH".equals(summary.get("failureCode"))
                        && summary.get("message") != null));
    }

    @Test
    void snapshotFailurePersistsTheWorkerFailureCodeAfterAsyncClaim() {
        UUID runId = UUID.randomUUID(), snapshotWorkspaceId = UUID.randomUUID();
        TestRunEntity run = new TestRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setTaskId(UUID.randomUUID()); run.setExecutionWorkspaceId(snapshotWorkspaceId);
        run.setExecutionSnapshot(List.of());
        when(testRuns.selectById(runId)).thenReturn(run);
        when(testRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        TaskEntity task = new TaskEntity(); task.setId(run.getTaskId()); task.setProjectId(run.getProjectId());
        task.setWorkspaceId(UUID.randomUUID());
        when(tasks.selectById(run.getTaskId())).thenReturn(task);
        doThrow(new ApiException(HttpStatus.BAD_GATEWAY, "SANDBOX_WORKER_UNAVAILABLE", "worker down"))
                .when(worker).createTestSnapshot(any(), any(), any(), any(), any());
        when(testRuns.complete(eq(runId), anyString(), eq("FAILED"), any())).thenReturn(1);

        service.executeTestRun(runId);

        verify(testRuns).complete(eq(runId), anyString(), eq("FAILED"),
                argThat(summary -> "SANDBOX_WORKER_UNAVAILABLE".equals(summary.get("failureCode"))
                        && summary.get("message") != null));
        verify(worker, never()).executeTests(any());
    }

    @Test
    void losingClaimDoesNotCallWorker() {
        UUID runId = UUID.randomUUID();
        when(testRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(0);
        service.executeTestRun(runId);
        verifyNoInteractions(worker);
    }

    @Test
    void dryRunTestsPersistedGateSnapshotOnTemporaryMergeTree() {
        UUID runId = UUID.randomUUID(), testsetId = UUID.randomUUID();
        DryRunEntity run = new DryRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setStatus("RUNNING"); run.setSourceRef("feat/login"); run.setHeadCommit("head");
        run.setTargetBranch("main"); run.setResolvedTargetCommit("target");
        run.setTestsetSnapshot(List.of(Map.of("testsetId", testsetId.toString(), "command", "mvn test",
                "timeoutSeconds", 60, "passRuleType", "EXIT_CODE", "expectedExitCode", 0)));
        when(dryRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        when(dryRuns.selectById(runId)).thenReturn(run);
        WorkerMergePreviewResponse preview = new WorkerMergePreviewResponse();
        preview.setMergeable(true); preview.setResolvedHeadCommit("head"); preview.setResolvedTargetCommit("target");
        when(worker.mergePreview(any())).thenReturn(preview);
        WorkerTestExecutionResponse tests = new WorkerTestExecutionResponse();
        tests.setStatus("PASSED"); tests.setResolvedHeadCommit("target");
        tests.setResolvedSourceCommit("head"); tests.setResolvedTargetCommit("target"); tests.setResults(List.of());
        when(worker.executeTests(any())).thenReturn(tests);
        when(dryRuns.complete(eq(runId), anyString(), eq("PASSED"), any(), eq("head"))).thenReturn(1);

        service.executeDryRun(runId);

        verify(worker).mergePreview(argThat(request -> "head".equals(request.getSourceRef())
                && "target".equals(request.getTargetCommit())));
        verify(worker).executeTests(argThat(request -> "target".equals(request.getRef())
                && "head".equals(request.getMergeSourceRef())));
    }

    @Test
    void dryRunRejectsWorkerPreviewForAnotherFrozenGitContext() {
        UUID runId = UUID.randomUUID();
        DryRunEntity run = new DryRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setHeadCommit("expected-head"); run.setResolvedTargetCommit("expected-target");
        run.setTestsetSnapshot(List.of());
        when(dryRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        when(dryRuns.selectById(runId)).thenReturn(run);
        WorkerMergePreviewResponse preview = new WorkerMergePreviewResponse();
        preview.setMergeable(true); preview.setResolvedHeadCommit("expected-head");
        preview.setResolvedTargetCommit("different-target");
        when(worker.mergePreview(any())).thenReturn(preview);
        when(dryRuns.complete(eq(runId), anyString(), eq("FAILED"), any(), isNull())).thenReturn(1);

        service.executeDryRun(runId);

        verify(dryRuns).complete(eq(runId), anyString(), eq("FAILED"),
                argThat(report -> "DRY_RUN_CONTEXT_MISMATCH".equals(report.get("failureCode"))), isNull());
        verify(worker, never()).executeTests(any());
    }

    @Test
    void dryRunDoesNotAcceptPassedTestsReportedForAnotherTargetCommit() {
        UUID runId = UUID.randomUUID(), testsetId = UUID.randomUUID();
        DryRunEntity run = new DryRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setHeadCommit("head"); run.setResolvedTargetCommit("target");
        run.setTestsetSnapshot(List.of(Map.of("testsetId", testsetId.toString(), "command", "mvn test",
                "timeoutSeconds", 60, "passRuleType", "EXIT_CODE", "expectedExitCode", 0)));
        when(dryRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        when(dryRuns.selectById(runId)).thenReturn(run);
        WorkerMergePreviewResponse preview = new WorkerMergePreviewResponse();
        preview.setMergeable(true); preview.setResolvedHeadCommit("head"); preview.setResolvedTargetCommit("target");
        when(worker.mergePreview(any())).thenReturn(preview);
        WorkerTestExecutionResponse tests = new WorkerTestExecutionResponse();
        tests.setStatus("PASSED"); tests.setResolvedHeadCommit("wrong-target"); tests.setResults(List.of());
        when(worker.executeTests(any())).thenReturn(tests);
        when(dryRuns.complete(eq(runId), anyString(), eq("FAILED"), any(), isNull())).thenReturn(1);

        service.executeDryRun(runId);

        verify(dryRuns).complete(eq(runId), anyString(), eq("FAILED"),
                argThat(report -> "DRY_RUN_TEST_CONTEXT_MISMATCH".equals(report.get("failureCode"))), isNull());
    }

    @Test
    void dryRunWithoutMandatoryTestsetsReportsNotRequiredInsteadOfFakingPass() {
        UUID runId = UUID.randomUUID();
        DryRunEntity run = new DryRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setStatus("RUNNING"); run.setSourceRef("feat/login"); run.setHeadCommit("head");
        run.setTargetBranch("main"); run.setResolvedTargetCommit("target");
        run.setTestsetSnapshot(List.of());
        when(dryRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        when(dryRuns.selectById(runId)).thenReturn(run);
        WorkerMergePreviewResponse preview = new WorkerMergePreviewResponse();
        preview.setMergeable(true); preview.setResolvedHeadCommit("head"); preview.setResolvedTargetCommit("target");
        when(worker.mergePreview(any())).thenReturn(preview);
        when(dryRuns.complete(eq(runId), anyString(), eq("PASSED"), any(), eq("head"))).thenReturn(1);

        service.executeDryRun(runId);

        verify(dryRuns).complete(eq(runId), anyString(), eq("PASSED"),
                argThat(report -> "NOT_REQUIRED".equals(((Map<?, ?>) report.get("tests")).get("status"))
                        && ((List<?>) ((Map<?, ?>) report.get("tests")).get("results")).isEmpty()), eq("head"));
        verify(worker, never()).executeTests(any());
    }

    @Test
    void dryRunMergeConflictSkipsTestsAndDoesNotFakeAPass() {
        UUID runId = UUID.randomUUID(), testsetId = UUID.randomUUID();
        DryRunEntity run = new DryRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setStatus("RUNNING"); run.setSourceRef("feat/login"); run.setHeadCommit("head");
        run.setTargetBranch("main"); run.setResolvedTargetCommit("target");
        run.setTestsetSnapshot(List.of(Map.of("testsetId", testsetId.toString(), "command", "mvn test",
                "timeoutSeconds", 60, "passRuleType", "EXIT_CODE", "expectedExitCode", 0)));
        when(dryRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        when(dryRuns.selectById(runId)).thenReturn(run);
        WorkerMergePreviewResponse preview = new WorkerMergePreviewResponse();
        preview.setMergeable(false); preview.setResolvedHeadCommit("head"); preview.setResolvedTargetCommit("target");
        preview.setConflicts(List.of("a.ts: conflict"));
        when(worker.mergePreview(any())).thenReturn(preview);
        when(dryRuns.complete(eq(runId), anyString(), eq("FAILED"), any(), eq("head"))).thenReturn(1);

        service.executeDryRun(runId);

        verify(dryRuns).complete(eq(runId), anyString(), eq("FAILED"),
                argThat(report -> Boolean.FALSE.equals(report.get("mergeable"))
                        && "SKIPPED".equals(((Map<?, ?>) report.get("tests")).get("status"))
                        && "MERGE_CONFLICT".equals(((Map<?, ?>) report.get("tests")).get("reason"))), eq("head"));
        verify(worker, never()).executeTests(any());
    }

    @Test
    void dryRunPersistsTestResultsInsideReportTests() {
        UUID runId = UUID.randomUUID(), testsetId = UUID.randomUUID();
        DryRunEntity run = new DryRunEntity();
        run.setId(runId); run.setProjectId(UUID.randomUUID()); run.setProjectRepositoryId(UUID.randomUUID());
        run.setStatus("RUNNING"); run.setSourceRef("feat/login"); run.setHeadCommit("head");
        run.setTargetBranch("main"); run.setResolvedTargetCommit("target");
        run.setTestsetSnapshot(List.of(Map.of("testsetId", testsetId.toString(), "command", "mvn test",
                "timeoutSeconds", 60, "passRuleType", "EXIT_CODE", "expectedExitCode", 0)));
        when(dryRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        when(dryRuns.selectById(runId)).thenReturn(run);
        WorkerMergePreviewResponse preview = new WorkerMergePreviewResponse();
        preview.setMergeable(true); preview.setResolvedHeadCommit("head"); preview.setResolvedTargetCommit("target");
        when(worker.mergePreview(any())).thenReturn(preview);
        WorkerTestExecutionItemResponse item = new WorkerTestExecutionItemResponse();
        item.setTestsetId(testsetId); item.setStatus("PASSED"); item.setExitCode(0);
        item.setDurationMs(69); item.setFailureCode(null);
        WorkerTestExecutionResponse tests = new WorkerTestExecutionResponse();
        tests.setStatus("PASSED"); tests.setResolvedHeadCommit("target");
        tests.setResolvedSourceCommit("head"); tests.setResolvedTargetCommit("target"); tests.setResults(List.of(item));
        when(worker.executeTests(any())).thenReturn(tests);
        when(dryRuns.complete(eq(runId), anyString(), eq("PASSED"), any(), eq("head"))).thenReturn(1);

        service.executeDryRun(runId);

        verify(dryRuns).complete(eq(runId), anyString(), eq("PASSED"),
                argThat(report -> {
                    Map<?, ?> testsBlock = (Map<?, ?>) report.get("tests");
                    if (!"PASSED".equals(testsBlock.get("status"))
                            || !"target".equals(testsBlock.get("resolvedHeadCommit"))) return false;
                    List<?> results = (List<?>) testsBlock.get("results");
                    if (results.size() != 1) return false;
                    Map<?, ?> row = (Map<?, ?>) results.getFirst();
                    return testsetId.equals(row.get("testsetId"))
                            && "PASSED".equals(row.get("status"))
                            && Integer.valueOf(0).equals(row.get("exitCode"))
                            && Long.valueOf(69L).equals(row.get("durationMs"))
                            && row.get("failureCode") == null
                            && row.get("message") == null;
                }), eq("head"));
    }
}
