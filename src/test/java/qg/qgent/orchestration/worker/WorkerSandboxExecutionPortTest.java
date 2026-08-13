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
 * 并从执行日志聚合 stdout/stderr。纯 Mock，不启动 Spring。
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
    void executeReturnsExitCodeAndCollectedLogs() {
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
            terminal.setExitCode(0);
            return terminal;
        });
        when(client.getToolExecutionLogs(any(), anyLong(), anyInt())).thenAnswer(inv -> {
            long after = inv.getArgument(1);
            List<WorkerExecutionLogEntry> all = List.of(
                    new WorkerExecutionLogEntry(1, "STDOUT", "BUILD SUCCESS", null),
                    new WorkerExecutionLogEntry(2, "STDERR", "warn", null));
            List<WorkerExecutionLogEntry> page = all.stream().filter(e -> e.getSequence() > after).toList();
            long cursor = page.isEmpty() ? after : page.get(page.size() - 1).getSequence();
            return new WorkerExecutionLogs(page, cursor);
        });

        ExecutionResult result = port.execute(WORKSPACE, List.of("mvn", "test"), Duration.ofMinutes(1));

        assertThat(result.ok()).isTrue();
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(result.stdout()).isEqualTo("BUILD SUCCESS");
        assertThat(result.stderr()).isEqualTo("warn");
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
        when(client.getToolExecutionLogs(any(), anyLong(), anyInt()))
                .thenReturn(new WorkerExecutionLogs(List.of(), 0));

        ExecutionResult result = port.execute(WORKSPACE, List.of("mvn", "test"), Duration.ofMinutes(1));

        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.error()).contains("超时");
    }
}
