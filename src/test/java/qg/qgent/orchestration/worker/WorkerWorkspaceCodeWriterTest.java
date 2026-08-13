package qg.qgent.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import qg.qgent.orchestration.tool.WorkspaceWriteResult;

/**
 * {@link WorkerWorkspaceCodeWriter} 单测：read-then-write 的 expectedHash 流程、
 * 写失败→工具级失败、路径→仓库解析。纯 Mock，不启动 Spring。
 */
class WorkerWorkspaceCodeWriterTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SANDBOX = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REPO = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private SandboxWorkerClient client;
    private SandboxSessionManager sessions;
    private WorkerWorkspaceCodeWriter writer;

    @BeforeEach
    void setUp() {
        client = mock(SandboxWorkerClient.class);
        sessions = mock(SandboxSessionManager.class);
        writer = new WorkerWorkspaceCodeWriter(client, sessions, new SandboxWorkerProperties());
    }

    private SandboxSession session() {
        return new SandboxSession(UUID.randomUUID(), WORKSPACE, SANDBOX, "workspaces/" + WORKSPACE,
                List.of(REPO), Map.of("repo-1", REPO));
    }

    private void stubToolExecution(Function<WorkerToolExecutionRequest, WorkerToolExecution> terminal) {
        Map<UUID, WorkerToolExecution> byId = new HashMap<>();
        when(client.submitToolExecution(any(), any())).thenAnswer(inv -> {
            WorkerToolExecutionRequest request = inv.getArgument(1);
            WorkerToolExecution queued = new WorkerToolExecution();
            queued.setId(request.getExecutionId());
            queued.setStatus("QUEUED");
            byId.put(request.getExecutionId(), terminal.apply(request));
            return queued;
        });
        when(client.getToolExecution(any())).thenAnswer(inv -> byId.get(inv.getArgument(0)));
    }

    @Test
    void writeFileReadsCurrentHashThenWritesWithExpectedHash() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> {
            WorkerToolExecution execution = new WorkerToolExecution();
            execution.setStatus("SUCCEEDED");
            if ("file.read".equals(request.getTool())) {
                execution.setResult(Map.of("path", request.getArguments().get("path"), "sha256", "current-hash"));
            } else {
                execution.setResult(Map.of("path", request.getArguments().get("path"),
                        "sha256", "new-hash", "bytes", 5));
            }
            return execution;
        });

        WorkspaceWriteResult result = writer.writeFile(WORKSPACE, "repo-1/src/Foo.java", "hello");

        assertThat(result.isOk()).isTrue();
        ArgumentCaptor<WorkerToolExecutionRequest> captor =
                ArgumentCaptor.forClass(WorkerToolExecutionRequest.class);
        verify(client, times(2)).submitToolExecution(any(), captor.capture());
        WorkerToolExecutionRequest write = captor.getAllValues().get(1);
        assertThat(write.getTool()).isEqualTo("file.write");
        assertThat(write.getArguments().get("expectedHash")).isEqualTo("current-hash");
        assertThat(write.getArguments().get("content")).isEqualTo("hello");
    }

    @Test
    void writeFileMapsWriteFailureToToolFailure() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> {
            WorkerToolExecution execution = new WorkerToolExecution();
            if ("file.read".equals(request.getTool())) {
                execution.setStatus("SUCCEEDED");
                execution.setResult(Map.of("path", request.getArguments().get("path"), "sha256", "hash"));
            } else {
                execution.setStatus("FAILED");
                execution.setFailureReason("文件已经发生变化，请重新读取后再写入");
            }
            return execution;
        });

        WorkspaceWriteResult result = writer.writeFile(WORKSPACE, "repo-1/src/Foo.java", "hello");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isFalse();
        assertThat(result.getError()).contains("发生变化");
    }
}
