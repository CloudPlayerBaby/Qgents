package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * legacy JSON 工具调用在 Worker 返回脱敏说明时仍应消费结构化 failureCode。
 */
class CodingToolExecutorTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void workerFailureCodeClassifiesPatchFailureWithoutTextPrefix() throws Exception {
        WorkspaceCodeAccess access = mock(WorkspaceCodeAccess.class);
        WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
        when(writer.patchFile(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_PATCH_FAILED",
                        "hunk 声明行数与正文不一致"));
        CodingToolExecutor executor = new CodingToolExecutor(access, writer);
        ObjectMapper mapper = new ObjectMapper();

        String raw = executor.execute(UUID.randomUUID(), mapper.readTree("""
                {"name":"apply_patch","arguments":{"path":"src/main/java/X.java","expectedHash":"%s","patch":"patch"}}
                """.formatted(HASH)));

        assertThat(mapper.readTree(raw).path("ok").asBoolean()).isFalse();
        assertThat(mapper.readTree(raw).path("errorCode").asText()).isEqualTo("TOOL_PATCH_FORMAT_INVALID");
        assertThat(mapper.readTree(raw).path("retryable").asBoolean()).isTrue();
    }

    @Test
    void workerFailureCodeClassifiesHashConflictWithoutTextPrefix() throws Exception {
        WorkspaceCodeAccess access = mock(WorkspaceCodeAccess.class);
        WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
        when(writer.patchFile(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(WorkspaceWriteResult.fail("src/main/java/X.java", "FILE_HASH_MISMATCH",
                        "文件已经发生变化，请重新读取后再写入"));
        CodingToolExecutor executor = new CodingToolExecutor(access, writer);
        ObjectMapper mapper = new ObjectMapper();

        String raw = executor.execute(UUID.randomUUID(), mapper.readTree("""
                {"name":"apply_patch","arguments":{"path":"src/main/java/X.java","expectedHash":"%s","patch":"patch"}}
                """.formatted(HASH)));

        assertThat(mapper.readTree(raw).path("ok").asBoolean()).isFalse();
        assertThat(mapper.readTree(raw).path("errorCode").asText()).isEqualTo("TOOL_CONFLICT");
        assertThat(mapper.readTree(raw).path("retryable").asBoolean()).isTrue();
    }

    @Test
    void replaceFileUsesExpectedHashAndReturnsSuccess() throws Exception {
        WorkspaceCodeAccess access = mock(WorkspaceCodeAccess.class);
        WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
        when(writer.replaceFile(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("src/main/java/X.java"),
                org.mockito.ArgumentMatchers.eq(HASH), org.mockito.ArgumentMatchers.eq("full content")))
                .thenReturn(WorkspaceWriteResult.ok("src/main/java/X.java", "a".repeat(64), true));
        CodingToolExecutor executor = new CodingToolExecutor(access, writer);
        ObjectMapper mapper = new ObjectMapper();

        String raw = executor.execute(UUID.randomUUID(), mapper.readTree("""
                {"name":"replace_file","arguments":{"path":"src/main/java/X.java","expectedHash":"%s","content":"full content"}}
                """.formatted(HASH)));

        assertThat(mapper.readTree(raw).path("ok").asBoolean()).isTrue();
        assertThat(mapper.readTree(raw).path("result").path("changed").asBoolean()).isTrue();
    }
}
