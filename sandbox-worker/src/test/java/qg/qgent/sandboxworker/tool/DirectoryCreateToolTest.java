package qg.qgent.sandboxworker.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.api.WorkerException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryCreateToolTest {
    @TempDir
    Path testRoot;

    private Path repository;

    @BeforeEach
    void setUp() throws IOException {
        repository = Files.createDirectory(testRoot.resolve("repository"));
    }

    @Test
    void recursivelyCreatesMissingDirectories() {
        ToolResult result = tool().execute(context(), Map.of("path", "src/main/java"));

        assertEquals(true, result.getResult().get("created"));
        assertTrue(Files.isDirectory(repository.resolve("src/main/java")));
    }

    @Test
    void existingDirectoryIsIdempotent() throws Exception {
        Files.createDirectories(repository.resolve("src/main"));

        ToolResult result = tool().execute(context(), Map.of("path", "src/main"));

        assertEquals(false, result.getResult().get("created"));
    }

    @Test
    void rejectsExistingFileAndTraversal() throws Exception {
        Files.writeString(repository.resolve("file.txt"), "content");

        WorkerException file = assertThrows(WorkerException.class,
                () -> tool().execute(context(), Map.of("path", "file.txt")));
        assertEquals("TOOL_PATH_INVALID", file.getCode());

        WorkerException traversal = assertThrows(WorkerException.class,
                () -> tool().execute(context(), Map.of("path", "../outside")));
        assertEquals("TOOL_PATH_INVALID", traversal.getCode());
    }

    @Test
    void rejectsSymlinkTargetWhenSupported() throws Exception {
        Path outside = Files.createDirectory(repository.resolve("outside-dir"));
        Path link = repository.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            return;
        }

        WorkerException exception = assertThrows(WorkerException.class,
                () -> tool().execute(context(), Map.of("path", "link")));
        assertEquals("TOOL_PATH_INVALID", exception.getCode());
    }

    @Test
    void rejectsParentDirectorySymlinkEscapeWhenSupported() throws Exception {
        Path outside = Files.createDirectory(testRoot.resolve("outside"));
        Path link = repository.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            return;
        }

        WorkerException exception = assertThrows(WorkerException.class,
                () -> tool().execute(context(), Map.of("path", "linked/child")));

        assertEquals("TOOL_PATH_INVALID", exception.getCode());
        assertTrue(Files.notExists(outside.resolve("child")));
    }

    private DirectoryCreateTool tool() {
        return new DirectoryCreateTool(new RepositoryFileResolver());
    }

    private ToolContext context() {
        return new ToolContext(null, null, repository, "/workspace/repository", null);
    }
}
