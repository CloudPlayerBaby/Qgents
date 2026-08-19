package qg.qgent.sandboxworker.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.api.WorkerException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {
    @TempDir
    Path repository;

    @Test
    void discoversAndExecutesFilePatch() throws Exception {
        Path file = repository.resolve("note.txt");
        Files.writeString(file, "one\ntwo\nthree\n", StandardCharsets.UTF_8);
        ToolRegistry registry = new ToolRegistry(List.of(new FilePatchTool(new RepositoryFileResolver())));
        String patch = """
                @@ -2,1 +2,1 @@
                -two
                +TWO
                """;

        ToolResult result = registry.execute("file.patch", context(), Map.of("path", "note.txt",
                "expectedHash", FileReadTool.sha256(Files.readAllBytes(file)), "patch", patch));

        assertEquals("one\nTWO\nthree\n", Files.readString(file));
        assertEquals(0, result.getExitCode());
        assertTrue(registry.requiresRepository("file.patch"));
    }

    @Test
    void discoversDirectoryCreate() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(new DirectoryCreateTool(new RepositoryFileResolver())));

        ToolResult result = registry.execute("directory.create", context(), Map.of("path", "new/dir"));

        assertEquals(true, result.getResult().get("created"));
        assertTrue(registry.requiresRepository("directory.create"));
    }

    @Test
    void unknownToolStillReturnsToolNotSupported() {
        ToolRegistry registry = new ToolRegistry(List.of(new FilePatchTool(new RepositoryFileResolver())));

        WorkerException exception = assertThrows(WorkerException.class,
                () -> registry.execute("file.nope", context(), Map.of()));

        assertEquals("TOOL_NOT_SUPPORTED", exception.getCode());
    }

    private ToolContext context() {
        return new ToolContext(null, null, repository, "/workspace/note", null);
    }
}
