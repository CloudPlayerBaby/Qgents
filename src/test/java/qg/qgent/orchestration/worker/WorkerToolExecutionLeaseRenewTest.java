package qg.qgent.orchestration.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.orchestration.tool.ExecutionResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Worker 工具执行沙箱租约续租测试：
 * 验证工具执行前立即续租、轮询期间定时续租、终态停止续租、续租失败阻止提交及 404 清理映射。
 */
class WorkerToolExecutionLeaseRenewTest {

    private final SandboxWorkerClient client = mock(SandboxWorkerClient.class);
    private final SandboxSessionManager sessions = mock(SandboxSessionManager.class);
    private final SandboxWorkerProperties properties = new SandboxWorkerProperties();

    private final UUID workspaceId = UUID.randomUUID();
    private final UUID repositoryId = UUID.randomUUID();
    private final UUID sandboxId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        properties.setPollInterval(Duration.ofMillis(1));
        properties.setPollTimeout(Duration.ofSeconds(300));
        properties.setLeaseRenewInterval(Duration.ofSeconds(10));

        SandboxSession session = new SandboxSession(UUID.randomUUID(), workspaceId, sandboxId, "key",
                List.of(repositoryId), Map.of(".", repositoryId));
        when(sessions.require(workspaceId)).thenReturn(session);
    }

    private TestWorkerToolPort createPort(Supplier<Long> nanoTimeSupplier) {
        return new TestWorkerToolPort(client, sessions, properties, nanoTimeSupplier);
    }

    @Test
    void renewCalledBeforeToolSubmission() {
        WorkerToolExecution queued = new WorkerToolExecution();
        queued.setId(executionId);
        queued.setStatus("QUEUED");

        WorkerToolExecution succeeded = new WorkerToolExecution();
        succeeded.setId(executionId);
        succeeded.setStatus("SUCCEEDED");
        succeeded.setExitCode(0);

        when(client.renewSandbox(sandboxId)).thenReturn(new WorkerSandbox());
        when(client.submitToolExecution(eq(sandboxId), any())).thenReturn(queued);
        when(client.getToolExecution(executionId)).thenReturn(succeeded);

        TestWorkerToolPort port = createPort(System::nanoTime);
        WorkerToolExecution result = port.runTool(workspaceId, repositoryId);

        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        verify(client, times(1)).renewSandbox(sandboxId);
        verify(client, times(1)).submitToolExecution(eq(sandboxId), any());
    }

    @Test
    void renewNotCalledRepeatedlyWhenIntervalNotReached() {
        AtomicLong timeNanos = new AtomicLong(0);

        WorkerToolExecution queued = new WorkerToolExecution();
        queued.setId(executionId);
        queued.setStatus("QUEUED");

        WorkerToolExecution running = new WorkerToolExecution();
        running.setId(executionId);
        running.setStatus("RUNNING");

        WorkerToolExecution succeeded = new WorkerToolExecution();
        succeeded.setId(executionId);
        succeeded.setStatus("SUCCEEDED");

        when(client.renewSandbox(sandboxId)).thenReturn(new WorkerSandbox());
        when(client.submitToolExecution(eq(sandboxId), any())).thenReturn(queued);

        // First poll: RUNNING at t=1s (less than 10s interval)
        // Second poll: SUCCEEDED at t=2s
        when(client.getToolExecution(executionId)).thenAnswer(inv -> {
            long current = timeNanos.addAndGet(Duration.ofSeconds(1).toNanos());
            return current < Duration.ofSeconds(2).toNanos() ? running : succeeded;
        });

        TestWorkerToolPort port = createPort(timeNanos::get);
        WorkerToolExecution result = port.runTool(workspaceId, repositoryId);

        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        // Only 1 renew call (the pre-execution renew)
        verify(client, times(1)).renewSandbox(sandboxId);
    }

    @Test
    void renewCalledAgainWhenIntervalReached() {
        AtomicLong timeNanos = new AtomicLong(0);

        WorkerToolExecution queued = new WorkerToolExecution();
        queued.setId(executionId);
        queued.setStatus("QUEUED");

        WorkerToolExecution running = new WorkerToolExecution();
        running.setId(executionId);
        running.setStatus("RUNNING");

        WorkerToolExecution succeeded = new WorkerToolExecution();
        succeeded.setId(executionId);
        succeeded.setStatus("SUCCEEDED");

        when(client.renewSandbox(sandboxId)).thenReturn(new WorkerSandbox());
        when(client.submitToolExecution(eq(sandboxId), any())).thenReturn(queued);

        // Advance time past 10s leaseRenewInterval during polling
        when(client.getToolExecution(executionId)).thenAnswer(inv -> {
            long current = timeNanos.addAndGet(Duration.ofSeconds(6).toNanos());
            return current < Duration.ofSeconds(15).toNanos() ? running : succeeded;
        });

        TestWorkerToolPort port = createPort(timeNanos::get);
        WorkerToolExecution result = port.runTool(workspaceId, repositoryId);

        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        // 2 renew calls: 1 initial pre-execution + 1 periodic renewal during polling
        verify(client, times(2)).renewSandbox(sandboxId);
    }

    @Test
    void noExtraRenewAfterTerminalState() {
        AtomicLong timeNanos = new AtomicLong(0);

        WorkerToolExecution queued = new WorkerToolExecution();
        queued.setId(executionId);
        queued.setStatus("QUEUED");

        WorkerToolExecution succeeded = new WorkerToolExecution();
        succeeded.setId(executionId);
        succeeded.setStatus("SUCCEEDED");

        when(client.renewSandbox(sandboxId)).thenReturn(new WorkerSandbox());
        when(client.submitToolExecution(eq(sandboxId), any())).thenReturn(queued);
        when(client.getToolExecution(executionId)).thenAnswer(inv -> {
            timeNanos.addAndGet(Duration.ofSeconds(20).toNanos());
            return succeeded;
        });

        TestWorkerToolPort port = createPort(timeNanos::get);
        WorkerToolExecution result = port.runTool(workspaceId, repositoryId);

        assertThat(result.getStatus()).isEqualTo("SUCCEEDED");
        // Must NOT renew after terminal state reached
        verify(client, times(1)).renewSandbox(sandboxId);
    }

    @Test
    void toolNotSubmittedIfRenewFails() {
        when(client.renewSandbox(sandboxId))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "SANDBOX_WORKER_UNAVAILABLE", "Worker down"));

        TestWorkerToolPort port = createPort(System::nanoTime);

        assertThatThrownBy(() -> port.runTool(workspaceId, repositoryId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Worker down");

        verify(client, never()).submitToolExecution(any(), any());
    }

    @Test
    void sandboxNotFound404HandledAsExecutionFailureInExecutionPort() {
        WorkerSandboxExecutionPort executionPort = new WorkerSandboxExecutionPort(client, sessions, properties);

        when(client.renewSandbox(sandboxId))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "SANDBOX_NOT_FOUND", "Sandbox missing"));

        ExecutionResult result = executionPort.execute(workspaceId, List.of("ls"), Duration.ofSeconds(10));

        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.error()).contains("Sandbox missing");
        verify(client, never()).submitToolExecution(any(), any());
    }

    /**
     * 用于测试 AbstractWorkerToolPort 抽象基类的具体子类。
     */
    private static class TestWorkerToolPort extends AbstractWorkerToolPort {
        TestWorkerToolPort(SandboxWorkerClient client, SandboxSessionManager sessions,
                SandboxWorkerProperties properties, Supplier<Long> nanoTimeSupplier) {
            super(client, sessions, properties, nanoTimeSupplier);
        }

        public WorkerToolExecution runTool(UUID workspaceId, UUID repositoryId) {
            return executeTool(workspaceId, repositoryId, "test.tool", Map.of(), Duration.ofSeconds(30));
        }
    }
}
