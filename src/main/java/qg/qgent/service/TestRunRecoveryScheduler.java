package qg.qgent.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import qg.qgent.entity.TestRunEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.TestRunMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 扫描未领取或租约过期的执行，并通过独立异步 Bean 调度，避免 self-invocation。
 */
@Service
public class TestRunRecoveryScheduler {
    private final TestRunMapper testRuns;
    private final DryRunMapper dryRuns;
    private final TestRunExecutionDispatcher dispatcher;
    private final SandboxWorkerClient worker;

    public TestRunRecoveryScheduler(TestRunMapper testRuns, DryRunMapper dryRuns,
                                    TestRunExecutionDispatcher dispatcher, SandboxWorkerClient worker) {
        this.testRuns = testRuns;
        this.dryRuns = dryRuns;
        this.dispatcher = dispatcher;
        this.worker = worker;
    }

    @Scheduled(fixedDelayString = "${qgents.test-execution.poll-delay-ms:5000}")
    public void recover() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        testRuns.selectRecoverable(now, 10).forEach(dispatcher::dispatchTestRun);
        dryRuns.selectRecoverable(now, 10).forEach(dispatcher::dispatchDryRun);
        testRuns.selectCleanupPending(20).forEach(this::cleanupSnapshot);
    }

    private void cleanupSnapshot(java.util.UUID runId) {
        TestRunEntity run = testRuns.selectById(runId);
        if (run == null || run.getExecutionWorkspaceId() == null) return;
        try {
            worker.deleteWorkspace(run.getExecutionWorkspaceId());
            testRuns.clearExecutionWorkspace(runId, run.getExecutionWorkspaceId());
        } catch (RuntimeException ignored) {
            // Worker 删除是幂等的；保留关联，下一轮继续清理。
        }
    }
}
