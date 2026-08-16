package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewTools 只读端口测试：结构上保证 Review Agent 只能读不能写——暴露的工具 schema 恰好是
 * list_files/read_file/search_code 三个，没有任何 write 工具；工具结果与失败语义与 CodingTools
 * 一致。不写入任何 API Key。
 */
class ReviewToolsTest {

    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final UUID workspaceId = UUID.randomUUID();

    private ReviewTools tools() {
        return new ReviewTools(workspaceId, codeAccess);
    }

    @Test
    void exposedToolSchemaIsExactlyTheReadOnlyWhitelist() {
        List<String> names = Arrays.stream(ToolCallbacks.from(new ReviewTools(workspaceId, codeAccess)))
                .map(c -> c.getToolDefinition().name())
                .sorted()
                .toList();
        assertThat(names).containsExactly("list_files", "read_file", "search_code");
    }

    @Test
    void noDeclaredToolCanWrite() {
        // 反射兜底：ReviewTools 上不得出现任何写工具名（结构性只读保证）。
        List<String> toolNames = Arrays.stream(ReviewTools.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(m -> m.getAnnotation(Tool.class).name())
                .toList();
        assertThat(toolNames).doesNotContain("write_file", "apply_patch");
    }

    @Test
    void listFilesReturnsOkWithPaths() {
        when(codeAccess.listFiles(workspaceId)).thenReturn(List.of("src/main/java/X.java"));

        Map<String, Object> result = tools().listFiles();

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("files")).isEqualTo(List.of("src/main/java/X.java"));
    }

    @Test
    void readFileReturnsContentAndSha256() {
        when(codeAccess.readFile(workspaceId, "src/main/java/X.java"))
                .thenReturn(WorkspaceFileReadResult.ok("src/main/java/X.java", "code", HASH));

        Map<String, Object> result = tools().readFile("src/main/java/X.java");

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("content")).isEqualTo("code");
        assertThat(result.get("sha256")).isEqualTo(HASH);
    }

    @Test
    void readFileMissingReturnsToolErrorWithoutException() {
        when(codeAccess.readFile(workspaceId, "src/main/java/Missing.java"))
                .thenReturn(WorkspaceFileReadResult.fail("src/main/java/Missing.java", "file not found or unreadable"));

        Map<String, Object> result = tools().readFile("src/main/java/Missing.java");

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("file not found or unreadable");
    }

    @Test
    void readFileBlankPathIsToolError() {
        Map<String, Object> result = tools().readFile("  ");

        assertThat(result.get("ok")).isEqualTo(false);
        verify(codeAccess, never()).readFile(any(), any());
    }
}
