package qg.qgent.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import qg.qgent.orchestration.tool.ExecutionResult;

/**
 * {@link WorkerSandboxExecutionPort} 单测：异步工具执行收敛为同步 {@link ExecutionResult}，
 * 只读取已脱敏日志并在主端再次处理，供 TestAgent 做环境/质量分流。纯 Mock，不启动 Spring。
 */
class WorkerSandboxExecutionPortTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SANDBOX = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REPO = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private SandboxWorkerClient client;
    private SandboxSessionManager sessions;
    private WorkerSandboxExecutionPort port;

    @BeforeEach
    void setUp() {
        client = mock(SandboxWorkerClient.class);
        sessions = mock(SandboxSessionManager.class);
        port = new WorkerSandboxExecutionPort(client, sessions, new SandboxWorkerProperties());
    }

    @Test
    void executeMapsFixedCommandAndReturnsRedactedDiagnosticsForTestClassification() {
        when(sessions.require(WORKSPACE)).thenReturn(new SandboxSession(UUID.randomUUID(), WORKSPACE, SANDBOX,
                "workspaces/" + WORKSPACE, List.of(REPO), Map.of("repo-1", REPO)));
        when(client.submitToolExecution(any(), any())).thenAnswer(inv -> {
            WorkerToolExecutionRequest request = inv.getArgument(1);
            WorkerToolExecution queued = new WorkerToolExecution();
            queued.setId(request.getExecutionId());
            queued.setStatus("QUEUED");
            return queued;
        });
        when(client.getToolExecution(any())).thenAnswer(inv -> {
            WorkerToolExecution terminal = new WorkerToolExecution();
            terminal.setId(inv.getArgument(0));
            terminal.setStatus("SUCCEEDED");
            terminal.setExitCode(1);
            return terminal;
        });
        when(client.getToolExecutionLogs(any(), anyLong(), anyInt())).thenAnswer(invocation -> {
            long after = invocation.getArgument(1);
            List<WorkerExecutionLogEntry> logs = List.of(
                    new WorkerExecutionLogEntry(1, "STDERR", "Connection refused to host mysql:3306", null),
                    new WorkerExecutionLogEntry(2, "STDERR", "TOKEN=should-not-leak", null),
                    new WorkerExecutionLogEntry(3, "STDERR", "JAVA_HOME=C:\\Users\\host", null),
                    new WorkerExecutionLogEntry(4, "STDERR", "file C:\\Users\\host\\build.log", null));
            List<WorkerExecutionLogEntry> page = logs.stream().filter(log -> log.getSequence() > after).toList();
            return new WorkerExecutionLogs(page, page.isEmpty() ? after : page.get(page.size() - 1).getSequence());
        });
        ExecutionResult result = port.execute(WORKSPACE, List.of("mvn", "test"), Duration.ofMinutes(1));

        assertThat(result.ok()).isTrue();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stdout()).isEmpty();
        assertThat(result.stderr()).contains("Connection refused to host mysql:3306")
                .contains("[environment omitted]").contains("[host path omitted]")
                .doesNotContain("should-not-leak").doesNotContain("C:\\Users\\host");
    }

    @Test
    void executeMapsMissingExitCodeToUnavailable() {
        when(sessions.require(WORKSPACE)).thenReturn(new SandboxSession(UUID.randomUUID(), WORKSPACE, SANDBOX,
                "workspaces/" + WORKSPACE, List.of(REPO), Map.of("repo-1", REPO)));
        when(client.submitToolExecution(any(), any())).thenAnswer(inv -> {
            WorkerToolExecutionRequest request = inv.getArgument(1);
            WorkerToolExecution queued = new WorkerToolExecution();
            queued.setId(request.getExecutionId());
            queued.setStatus("QUEUED");
            return queued;
        });
        when(client.getToolExecution(any())).thenAnswer(inv -> {
            WorkerToolExecution terminal = new WorkerToolExecution();
            terminal.setId(inv.getArgument(0));
            terminal.setStatus("TIMED_OUT");
            terminal.setFailureReason("工具执行超时或被中断");
            return terminal;
        });
        ExecutionResult result = port.execute(WORKSPACE, List.of("mvn", "test"), Duration.ofMinutes(1));

        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.error()).isEqualTo("fixed development command failed");
    }
}
