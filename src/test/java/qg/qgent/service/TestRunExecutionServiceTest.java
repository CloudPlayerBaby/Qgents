package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.TestRunEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.TestRunMapper;
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
    private final TestRunExecutionService service = new TestRunExecutionService(testRuns, dryRuns, worker,
            mock(EventService.class));

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
        response.setStatus("PASSED"); response.setResults(List.of());
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
        run.setExecutionWorkspaceId(snapshotWorkspaceId); run.setExecutionSourceRef("original-head");
        run.setExecutionSnapshot(List.of(Map.of("testsetId", testsetId.toString(), "command", "mvn test",
                "timeoutSeconds", 60, "passRuleType", "EXIT_CODE", "expectedExitCode", 0)));
        when(testRuns.selectById(runId)).thenReturn(run);
        when(testRuns.claim(eq(runId), anyString(), any(), any())).thenReturn(1);
        WorkerTestExecutionResponse response = new WorkerTestExecutionResponse();
        response.setStatus("PASSED"); response.setResults(List.of());
        when(worker.executeTests(any())).thenReturn(response);
        when(testRuns.complete(eq(runId), anyString(), eq("PASSED"), any())).thenReturn(1);

        service.executeTestRun(runId);

        verify(worker).executeTests(argThat(request -> snapshotWorkspaceId.equals(request.getWorkspaceId())
                && request.getRef() == null));
        verify(worker).deleteWorkspace(snapshotWorkspaceId);
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
        tests.setStatus("PASSED"); tests.setResults(List.of());
        when(worker.executeTests(any())).thenReturn(tests);
        when(dryRuns.complete(eq(runId), anyString(), eq("PASSED"), any(), eq("head"))).thenReturn(1);

        service.executeDryRun(runId);

        verify(worker).mergePreview(argThat(request -> "head".equals(request.getSourceRef())
                && "target".equals(request.getTargetBranch())));
        verify(worker).executeTests(argThat(request -> "target".equals(request.getRef())
                && "head".equals(request.getMergeSourceRef())));
    }
}
