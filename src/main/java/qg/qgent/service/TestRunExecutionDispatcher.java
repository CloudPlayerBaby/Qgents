package qg.qgent.service;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** 跨 Bean 触发异步执行，避免 recover 中的 self-invocation 绕过 Spring 代理。 */
@Service
public class TestRunExecutionDispatcher {
    private final TestRunExecutionService executions;

    public TestRunExecutionDispatcher(TestRunExecutionService executions) {
        this.executions = executions;
    }

    @Async("testExecutionExecutor")
    public void dispatchTestRun(UUID runId) {
        executions.executeTestRun(runId);
    }

    @Async("testExecutionExecutor")
    public void dispatchDryRun(UUID runId) {
        executions.executeDryRun(runId);
    }
}
