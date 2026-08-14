package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.TestRunMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class TestRunRecoverySchedulerTest {
    @Test
    void recoveryDispatchesThroughSeparateAsyncBean() {
        TestRunMapper tests = mock(TestRunMapper.class);
        DryRunMapper dryRuns = mock(DryRunMapper.class);
        TestRunExecutionDispatcher dispatcher = mock(TestRunExecutionDispatcher.class);
        UUID testId = UUID.randomUUID(), dryId = UUID.randomUUID();
        when(tests.selectRecoverable(any(), eq(10))).thenReturn(List.of(testId));
        when(dryRuns.selectRecoverable(any(), eq(10))).thenReturn(List.of(dryId));

        when(tests.selectCleanupPending(20)).thenReturn(List.of());
        new TestRunRecoveryScheduler(tests, dryRuns, dispatcher, mock(SandboxWorkerClient.class)).recover();

        verify(dispatcher).dispatchTestRun(testId);
        verify(dispatcher).dispatchDryRun(dryId);
    }

    @Test
    void janitorRetriesIdempotentSnapshotDeletionAndClearsOnlyAfterSuccess() {
        TestRunMapper tests = mock(TestRunMapper.class);
        DryRunMapper dryRuns = mock(DryRunMapper.class);
        TestRunExecutionDispatcher dispatcher = mock(TestRunExecutionDispatcher.class);
        SandboxWorkerClient worker = mock(SandboxWorkerClient.class);
        UUID runId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        qg.qgent.entity.TestRunEntity run = new qg.qgent.entity.TestRunEntity();
        run.setId(runId); run.setExecutionWorkspaceId(workspaceId); run.setStatus("FAILED");
        when(tests.selectRecoverable(any(), eq(10))).thenReturn(List.of());
        when(dryRuns.selectRecoverable(any(), eq(10))).thenReturn(List.of());
        when(tests.selectCleanupPending(20)).thenReturn(List.of(runId));
        when(tests.selectById(runId)).thenReturn(run);
        doThrow(new RuntimeException("temporary failure")).doNothing().when(worker).deleteWorkspace(workspaceId);
        TestRunRecoveryScheduler scheduler = new TestRunRecoveryScheduler(tests, dryRuns, dispatcher, worker);

        scheduler.recover();
        verify(tests, never()).clearExecutionWorkspace(any(), any());
        scheduler.recover();
        verify(tests).clearExecutionWorkspace(runId, workspaceId);
    }
}
