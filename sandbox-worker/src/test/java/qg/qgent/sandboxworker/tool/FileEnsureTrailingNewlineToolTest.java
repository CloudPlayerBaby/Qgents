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
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileEnsureTrailingNewlineToolTest {
    @TempDir
    Path repository;

    @Test
    void appendsLfWhenFileLacksTrailingNewline() throws Exception {
        Path file = repository.resolve("a.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);

        ToolResult result = tool().execute(context(), Map.of("path", "a.txt",
                "expectedHash", FileReadTool.sha256(Files.readAllBytes(file))));

        assertEquals("hello\n", Files.readString(file));
        assertEquals(Boolean.TRUE, result.getResult().get("changed"));
        assertEquals(Boolean.TRUE, result.getResult().get("endsWithNewline"));
    }

    @Test
    void appendsCrlfWhenFileUsesCrlf() throws Exception {
        Path file = repository.resolve("crlf.txt");
        Files.write(file, "line1\r\nline2".getBytes(StandardCharsets.UTF_8));

        ToolResult result = tool().execute(context(), Map.of("path", "crlf.txt",
                "expectedHash", FileReadTool.sha256(Files.readAllBytes(file))));

        // 用字节比较：Files.readString 在部分平台会规范化 CRLF，无法验证换行风格。
        assertEquals("line1\r\nline2\r\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertEquals(Boolean.TRUE, result.getResult().get("changed"));
    }

    @Test
    void idempotentWhenFileAlreadyEndsWithNewline() throws Exception {
        Path file = repository.resolve("ok.txt");
        Files.writeString(file, "done\n", StandardCharsets.UTF_8);

        ToolResult result = tool().execute(context(), Map.of("path", "ok.txt",
                "expectedHash", FileReadTool.sha256(Files.readAllBytes(file))));

        assertEquals("done\n", Files.readString(file));
        assertEquals(Boolean.FALSE, result.getResult().get("changed"));
        assertEquals(Boolean.TRUE, result.getResult().get("endsWithNewline"));
    }

    @Test
    void rejectsHashMismatch() throws Exception {
        Path file = repository.resolve("b.txt");
        Files.writeString(file, "content", StandardCharsets.UTF_8);

        WorkerException exception = assertThrows(WorkerException.class,
                () -> tool().execute(context(), Map.of("path", "b.txt",
                        "expectedHash", "0".repeat(64))));

        assertEquals("FILE_HASH_MISMATCH", exception.getCode());
        assertEquals("content", Files.readString(file));
    }

    @Test
    void rejectsNonExistentFile() {
        WorkerException exception = assertThrows(WorkerException.class,
                () -> tool().execute(context(), Map.of("path", "missing.txt",
                        "expectedHash", "0".repeat(64))));

        assertEquals("TOOL_PATH_INVALID", exception.getCode());
        assertTrue(Files.notExists(repository.resolve("missing.txt")));
    }

    private FileEnsureTrailingNewlineTool tool() {
        return new FileEnsureTrailingNewlineTool(new RepositoryFileResolver());
    }

    private ToolContext context() {
        return new ToolContext(null, null, repository, "/workspace/example", null);
    }
}
