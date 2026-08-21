package qg.qgent.orchestration.worker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.orchestration.tool.DevelopmentCommandId;
import qg.qgent.orchestration.tool.DevelopmentCommandResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Worker 固定开发命令端口测试：仓库绑定与 commandId 透传，不取回执行日志。 */
class WorkerDevelopmentCommandPortTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID SANDBOX = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final UUID REPO = UUID.fromString("00000000-0000-0000-0000-000000000013");

    private SandboxWorkerClient client;
    private SandboxSessionManager sessions;
    private WorkerDevelopmentCommandPort port;

    @BeforeEach
    void setUp() {
        client = mock(SandboxWorkerClient.class);
        sessions = mock(SandboxSessionManager.class);
        port = new WorkerDevelopmentCommandPort(client, sessions, new SandboxWorkerProperties());
    }

    @Test
    void submitsOnlyCommandIdToBoundRepositoryAndNeverFetchesLogs() {
        when(sessions.require(WORKSPACE)).thenReturn(new SandboxSession(UUID.randomUUID(), WORKSPACE, SANDBOX,
                "workspaces/opaque", List.of(REPO), Map.of("repo-1", REPO)));
        when(client.submitToolExecution(any(), any())).thenAnswer(invocation -> {
            WorkerToolExecutionRequest request = invocation.getArgument(1);
            WorkerToolExecution queued = new WorkerToolExecution();
            queued.setId(request.getExecutionId());
            queued.setStatus("QUEUED");
            return queued;
        });
        when(client.getToolExecution(any())).thenAnswer(invocation -> {
            WorkerToolExecution terminal = new WorkerToolExecution();
            terminal.setId(invocation.getArgument(0));
            terminal.setStatus("SUCCEEDED");
            terminal.setExitCode(0);
            return terminal;
        });

        DevelopmentCommandResult result = port.run(WORKSPACE, "repo-1", DevelopmentCommandId.MAVEN_TEST);

        ArgumentCaptor<WorkerToolExecutionRequest> request = ArgumentCaptor.forClass(WorkerToolExecutionRequest.class);
        verify(client).submitToolExecution(any(), request.capture());
        assertThat(request.getValue().getTool()).isEqualTo("development.run");
        assertThat(request.getValue().getRepositoryId()).isEqualTo(REPO);
        assertThat(request.getValue().getArguments()).containsOnly(Map.entry("commandId", "MAVEN_TEST"));
        assertThat(request.getValue().getArguments()).doesNotContainKeys("command", "argv", "cwd", "environment");
        verify(client, never()).getToolExecutionLogs(any(), anyLong(), anyInt());
        assertThat(result).isEqualTo(new DevelopmentCommandResult(true, "MAVEN_TEST", 0, null, null));
    }

    @Test
    void rejectsRepositoryOutsideCurrentWorkspaceBeforeCallingWorker() {
        when(sessions.require(WORKSPACE)).thenReturn(new SandboxSession(UUID.randomUUID(), WORKSPACE, SANDBOX,
                "workspaces/opaque", List.of(REPO), Map.of("repo-1", REPO)));

        DevelopmentCommandResult result = port.run(WORKSPACE, "not-bound", DevelopmentCommandId.NPM_TEST);

        assertThat(result.ok()).isFalse();
        assertThat(result.failureCode()).isEqualTo("WORKSPACE_REPOSITORY_NOT_FOUND");
        verify(client, never()).submitToolExecution(any(), any());
    }
}
