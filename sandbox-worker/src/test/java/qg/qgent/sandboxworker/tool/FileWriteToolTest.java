package qg.qgent.sandboxworker.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.api.WorkerException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileWriteToolTest {
    @TempDir
    Path repository;

    @Test
    void writesOnlyWhenExpectedHashMatches() throws Exception {
        Path file = repository.resolve("example.txt");
        Files.writeString(file, "旧内容", StandardCharsets.UTF_8);
        FileWriteTool tool = new FileWriteTool(new RepositoryFileResolver());
        String expectedHash = FileReadTool.sha256(Files.readAllBytes(file));

        ToolResult result = tool.execute(context(), Map.of("path", "example.txt", "expectedHash", expectedHash,
                "content", "新内容"));

        assertEquals("新内容", Files.readString(file));
        assertEquals(FileReadTool.sha256("新内容".getBytes(StandardCharsets.UTF_8)), result.getResult().get("sha256"));
    }

    @Test
    void rejectsWriteWhenFileChanged() throws Exception {
        Files.writeString(repository.resolve("example.txt"), "已经变化", StandardCharsets.UTF_8);
        FileWriteTool tool = new FileWriteTool(new RepositoryFileResolver());

        assertThrows(WorkerException.class, () -> tool.execute(context(), Map.of("path", "example.txt",
                "expectedHash", FileReadTool.sha256("旧内容".getBytes(StandardCharsets.UTF_8)), "content", "新内容")));
    }

    private ToolContext context() {
        return new ToolContext(null, null, repository, "/workspace/example", null);
    }
}
