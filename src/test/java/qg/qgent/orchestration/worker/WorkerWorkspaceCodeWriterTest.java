package qg.qgent.orchestration.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import qg.qgent.orchestration.tool.WorkspaceDirectoryResult;

/**
 * {@link WorkerWorkspaceCodeWriter} 单测：read-then-write 的 expectedHash 流程、
 * 写失败→工具级失败、路径→仓库解析。纯 Mock，不启动 Spring。
 */
class WorkerWorkspaceCodeWriterTest {

    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SANDBOX = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID REPO = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

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
    void createDirectorySubmitsDirectoryToolWithWorkspaceRelativePath() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> {
            WorkerToolExecution execution = new WorkerToolExecution();
            execution.setStatus("SUCCEEDED");
            execution.setResult(Map.of("path", request.getArguments().get("path"), "created", true));
            return execution;
        });

        WorkspaceDirectoryResult result = writer.createDirectory(WORKSPACE, "repo-1/src/main");

        assertThat(result.isOk()).isTrue();
        assertThat(result.isCreated()).isTrue();
        ArgumentCaptor<WorkerToolExecutionRequest> captor =
                ArgumentCaptor.forClass(WorkerToolExecutionRequest.class);
        verify(client).submitToolExecution(any(), captor.capture());
        assertThat(captor.getValue().getTool()).isEqualTo("directory.create");
        assertThat(captor.getValue().getArguments().get("path")).isEqualTo("src/main");
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
        // 兼容旧 Worker：缺少 changed 时，按写入前后的 SHA 推断为真实变更。
        assertThat(result.getNewSha256()).isEqualTo("new-hash");
        assertThat(result.isChanged()).isTrue();
        ArgumentCaptor<WorkerToolExecutionRequest> captor =
                ArgumentCaptor.forClass(WorkerToolExecutionRequest.class);
        verify(client, times(3)).submitToolExecution(any(), captor.capture());
        WorkerToolExecutionRequest write = captor.getAllValues().get(2);
        assertThat(write.getTool()).isEqualTo("file.write");
        assertThat(write.getArguments().get("expectedHash")).isEqualTo("current-hash");
        assertThat(write.getArguments().get("content")).isEqualTo("hello");
    }

    @Test
    void writeFilePassesThroughWorkerChangedFlag() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> {
            WorkerToolExecution execution = new WorkerToolExecution();
            execution.setStatus("SUCCEEDED");
            if ("file.read".equals(request.getTool())) {
                execution.setResult(Map.of("path", request.getArguments().get("path"), "sha256", "current-hash"));
            } else {
                execution.setResult(Map.of("path", request.getArguments().get("path"),
                        "sha256", "new-hash", "bytes", 5, "changed", true));
            }
            return execution;
        });

        WorkspaceWriteResult result = writer.writeFile(WORKSPACE, "repo-1/src/Foo.java", "hello");

        assertThat(result.isOk()).isTrue();
        assertThat(result.isChanged()).isTrue();
        assertThat(result.getNewSha256()).isEqualTo("new-hash");
    }

    @Test
    void writeFileTreatsMissingChangedAsUnchangedWhenShaIsIdentical() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> {
            WorkerToolExecution execution = new WorkerToolExecution();
            execution.setStatus("SUCCEEDED");
            execution.setResult(Map.of("path", request.getArguments().get("path"),
                    "sha256", "same-hash", "bytes", 5));
            return execution;
        });

        WorkspaceWriteResult result = writer.writeFile(WORKSPACE, "repo-1/src/Foo.java", "hello");

        assertThat(result.isOk()).isTrue();
        assertThat(result.isChanged()).isFalse();
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
                execution.setFailureCode("FILE_HASH_MISMATCH");
                execution.setFailureReason("文件已经发生变化，请重新读取后再写入");
            }
            return execution;
        });

        WorkspaceWriteResult result = writer.writeFile(WORKSPACE, "repo-1/src/Foo.java", "hello");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isFalse();
        assertThat(result.getError()).contains("发生变化");
    }

    @Test
    void patchFileSubmitsFilePatchWithExactArgs() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> {
            WorkerToolExecution execution = new WorkerToolExecution();
            execution.setStatus("SUCCEEDED");
            execution.setResult(Map.of("path", request.getArguments().get("path"),
                    "sha256", "new-hash", "bytes", 3, "changed", true));
            return execution;
        });

        WorkspaceWriteResult result = writer.patchFile(WORKSPACE, "repo-1/src/Foo.java", HASH,
                "@@ -1,1 +1,1 @@\n-a\n+b\n");

        assertThat(result.isOk()).isTrue();
        assertThat(result.getNewSha256()).isEqualTo("new-hash");
        assertThat(result.isChanged()).isTrue();
        ArgumentCaptor<WorkerToolExecutionRequest> captor =
                ArgumentCaptor.forClass(WorkerToolExecutionRequest.class);
        verify(client, times(1)).submitToolExecution(any(), captor.capture());
        WorkerToolExecutionRequest patch = captor.getValue();
        assertThat(patch.getTool()).isEqualTo("file.patch");
        assertThat(patch.getArguments().get("path")).isEqualTo("src/Foo.java");
        assertThat(patch.getArguments().get("expectedHash")).isEqualTo(HASH);
        assertThat(patch.getArguments().get("patch")).isEqualTo("@@ -1,1 +1,1 @@\n-a\n+b\n");
    }

    @Test
    void patchFileMapsWorkerFailureToToolFailure() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> {
            WorkerToolExecution execution = new WorkerToolExecution();
            execution.setStatus("FAILED");
            execution.setFailureCode("FILE_HASH_MISMATCH");
            execution.setFailureReason("文件已经发生变化");
            return execution;
        });

        WorkspaceWriteResult result = writer.patchFile(WORKSPACE, "repo-1/src/Foo.java", HASH,
                "@@ -1,1 +1,1 @@\n-a\n+b\n");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isFalse();
        assertThat(result.getError()).contains("发生变化");
    }

    @Test
    void patchFileMapsPatchFormatFailureToToolFailure() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> failed("FILE_PATCH_FAILED", "hunk 声明行数与正文不一致"));

        WorkspaceWriteResult result = writer.patchFile(WORKSPACE, "repo-1/src/Foo.java", HASH,
                "@@ -1,1 +1,1 @@\n-a\n+b\n");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isFalse();
        assertThat(result.getError()).contains("hunk 声明行数");
    }

    @Test
    void patchFileDoesNotClassifyFailureReasonPrefixWithoutMatchingFailureCode() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> failed("PROCESS_EXIT_NONZERO", "FILE_PATCH_FAILED: hunk 声明行数与正文不一致"));

        WorkspaceWriteResult result = writer.patchFile(WORKSPACE, "repo-1/src/Foo.java", HASH,
                "@@ -1,1 +1,1 @@\n-a\n+b\n");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isTrue();
        assertThat(result.getError()).contains("FILE_PATCH_FAILED");
    }

    @Test
    void patchFileTreatsGenericToolExecutionFailureAsInfrastructureFailure() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> failed("TOOL_EXECUTION_FAILED", "工具执行失败"));

        WorkspaceWriteResult result = writer.patchFile(WORKSPACE, "repo-1/src/Foo.java", HASH,
                "@@ -1,1 +1,1 @@\n-a\n+b\n");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isTrue();
    }

    @Test
    void patchFileMapsTransportFailureToInfrastructure() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        when(client.submitToolExecution(any(), any())).thenThrow(new IllegalStateException("worker down"));

        WorkspaceWriteResult result = writer.patchFile(WORKSPACE, "repo-1/src/Foo.java", HASH,
                "@@ -1,1 +1,1 @@\n-a\n+b\n");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isTrue();
        assertThat(result.getError()).contains("worker down");
    }

    @Test
    void patchFileFallsBackToExceptionTypeWhenInfraMessageIsNull() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        // 复现 "patch failed: null" 场景：Worker 工具链路抛出的 RuntimeException 无消息。
        when(client.submitToolExecution(any(), any())).thenThrow(new RuntimeException());

        WorkspaceWriteResult result = writer.patchFile(WORKSPACE, "repo-1/src/Foo.java", HASH,
                "@@ -1,1 +1,1 @@\n-a\n+b\n");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isTrue();
        assertThat(result.getError()).isEqualTo("patch failed: RuntimeException");
        assertThat(result.getError()).doesNotContain("null");
    }

    @Test
    void patchFileRejectsInvalidHashAndBlankArgs() {
        when(sessions.require(WORKSPACE)).thenReturn(session());

        assertThat(writer.patchFile(WORKSPACE, "repo-1/src/Foo.java", "not-a-hash",
                "@@ -1,1 +1,1 @@\n-a\n+b\n").isOk()).isFalse();
        assertThat(writer.patchFile(WORKSPACE, "repo-1/src/Foo.java", HASH, "  ").isOk()).isFalse();
        assertThat(writer.patchFile(WORKSPACE, "  ", HASH, "@@ -1,1 +1,1 @@\n-a\n+b\n").isOk()).isFalse();
        verify(client, never()).submitToolExecution(any(), any());
    }

    @Test
    void createDirectoryMapsPathFailureToToolFailure() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> failed("TOOL_PATH_INVALID", "目录路径不能包含符号链接"));

        WorkspaceDirectoryResult result = writer.createDirectory(WORKSPACE, "repo-1/linked/child");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isFalse();
        assertThat(result.getError()).contains("目录路径不能包含符号链接");
    }

    @Test
    void createDirectoryMapsUnclassifiedWorkerFailureToInfrastructure() {
        when(sessions.require(WORKSPACE)).thenReturn(session());
        stubToolExecution(request -> failed("DIRECTORY_CREATE_FAILED", "创建目录失败"));

        WorkspaceDirectoryResult result = writer.createDirectory(WORKSPACE, "repo-1/src/main");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isTrue();
        assertThat(result.getError()).contains("创建目录失败");
    }

    private static WorkerToolExecution failed(String failureCode, String failureReason) {
        WorkerToolExecution execution = new WorkerToolExecution();
        execution.setStatus("FAILED");
        execution.setFailureCode(failureCode);
        execution.setFailureReason(failureReason);
        return execution;
    }
}
