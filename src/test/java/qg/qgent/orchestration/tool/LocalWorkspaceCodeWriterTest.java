package qg.qgent.orchestration.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.service.WorkspaceService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LocalWorkspaceCodeWriter 单元测试：基于 {@code @TempDir} 的真实本地目录 + Mock Mapper，
 * 覆盖正常写入/自动建目录、空白与超限拒绝、路径穿越与绝对路径越界拒绝、Workspace
 * 不存在，以及 Workspace 服务异常向上传播。不写任何 Secret。
 */
class LocalWorkspaceCodeWriterTest {

    private static final int MAX_WRITE_BYTES = 256 * 1024;
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private final WorkspaceMapper mapper = mock(WorkspaceMapper.class);
    private final UUID workspaceId = UUID.randomUUID();

    private WorkspaceService service(String baseDir) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setStorageKey("ws-1");
        when(mapper.selectById(workspaceId)).thenReturn(workspace);
        return new WorkspaceService(mapper, baseDir);
    }

    @Test
    void createDirectoryRecursivelyAndIdempotently(@TempDir Path baseDir) {
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceDirectoryResult created = writer.createDirectory(workspaceId, "src/main/java");
        WorkspaceDirectoryResult existing = writer.createDirectory(workspaceId, "src/main/java");

        assertThat(created.isOk()).isTrue();
        assertThat(created.isCreated()).isTrue();
        assertThat(existing.isOk()).isTrue();
        assertThat(existing.isCreated()).isFalse();
        assertThat(Files.isDirectory(baseDir.resolve("ws-1/src/main/java"))).isTrue();
    }

    @Test
    void createDirectoryRejectsFileAndTraversal(@TempDir Path baseDir) throws Exception {
        Files.createDirectories(baseDir.resolve("ws-1"));
        Files.writeString(baseDir.resolve("ws-1/file.txt"), "content");
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceDirectoryResult file = writer.createDirectory(workspaceId, "file.txt");
        WorkspaceDirectoryResult traversal = writer.createDirectory(workspaceId, "../outside");

        assertThat(file.isOk()).isFalse();
        assertThat(file.isInfrastructureFailure()).isFalse();
        assertThat(traversal.isOk()).isFalse();
        assertThat(traversal.isInfrastructureFailure()).isFalse();
    }

    @Test
    void writeFilePersistsContentAndCreatesParentDirs(@TempDir Path baseDir) throws Exception {
        WorkspaceWriteResult result = new LocalWorkspaceCodeWriter(service(baseDir.toString()))
                .writeFile(workspaceId, "src/main/java/Y.java", "new code");

        assertThat(result.isOk()).isTrue();
        assertThat(result.getPath()).isEqualTo("src/main/java/Y.java");
        assertThat(result.isChanged()).isTrue();
        assertThat(result.getNewSha256())
                .isEqualTo(Sha256.hex("new code".getBytes(StandardCharsets.UTF_8)));
        Path written = baseDir.resolve("ws-1").resolve("src/main/java/Y.java");
        assertThat(Files.exists(written)).isTrue();
        assertThat(Files.readString(written)).isEqualTo("new code");
    }

    @Test
    void writeFileRejectsBlankPathAndNullContent(@TempDir Path baseDir) {
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        assertThat(writer.writeFile(workspaceId, "  ", "x").isOk()).isFalse();
        assertThat(writer.writeFile(workspaceId, "A.java", null).isOk()).isFalse();
    }

    @Test
    void writeFileRejectsPathTraversalAndNeverPersists(@TempDir Path baseDir) {
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceWriteResult result = writer.writeFile(workspaceId, "../escape.txt", "evil");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getError()).contains("escape");
        // 路径越界是工具级错误，不是基础设施失败。
        assertThat(result.isInfrastructureFailure()).isFalse();
        assertThat(Files.exists(baseDir.resolve("escape.txt"))).isFalse();
    }

    @Test
    void writeFileRejectsParentDirectorySymlinkEscape(@TempDir Path baseDir) throws Exception {
        Files.createDirectories(baseDir.resolve("ws-1"));
        Path outsideDir = Files.createDirectories(baseDir.resolve("outside"));
        Path victim = outsideDir.resolve("victim.txt");
        try {
            Files.createSymbolicLink(baseDir.resolve("ws-1/sub"), outsideDir);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink not supported on this platform");
        }

        WorkspaceWriteResult result = new LocalWorkspaceCodeWriter(service(baseDir.toString()))
                .writeFile(workspaceId, "sub/victim.txt", "changed");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isFalse();
        assertThat(result.getError()).contains("escape");
        assertThat(Files.exists(victim)).isFalse();
    }

    @Test
    void writeFileRejectsAbsolutePathAndNeverPersists(@TempDir Path baseDir) {
        String absolute = Path.of(".").toAbsolutePath().resolve("evil.txt").toString();
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceWriteResult result = writer.writeFile(workspaceId, absolute, "evil");

        assertThat(result.isOk()).isFalse();
        assertThat(Files.exists(Path.of(absolute))).isFalse();
    }

    @Test
    void writeFileRejectsOversizedContent(@TempDir Path baseDir) {
        String oversized = "x".repeat(MAX_WRITE_BYTES + 1);
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceWriteResult result = writer.writeFile(workspaceId, "big.java", oversized);

        assertThat(result.isOk()).isFalse();
        assertThat(result.getError()).contains("256KB");
        assertThat(Files.exists(baseDir.resolve("ws-1").resolve("big.java"))).isFalse();
    }

    @Test
    void writeToDirectoryPathFailsExplicitly(@TempDir Path baseDir) throws Exception {
        Files.createDirectories(baseDir.resolve("ws-1/occupied"));
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceWriteResult result = writer.writeFile(workspaceId, "occupied", "content");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getError()).contains("write failed");
        // 文件系统写入异常属于基础设施失败。
        assertThat(result.isInfrastructureFailure()).isTrue();
    }

    @Test
    void unknownWorkspaceFailsWithoutCreatingFiles(@TempDir Path baseDir) {
        UUID unknown = UUID.randomUUID();
        when(mapper.selectById(unknown)).thenReturn(null);
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceWriteResult result = writer.writeFile(unknown, "A.java", "x");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getError()).contains("root is not available");
        // workspace 不可用属于基础设施失败，CodingAgent 应映射 FAILED_INFRASTRUCTURE。
        assertThat(result.isInfrastructureFailure()).isTrue();
        assertThat(Files.exists(baseDir.resolve("ws-1").resolve("A.java"))).isFalse();
    }

    @Test
    void serviceFailurePropagatesNotSwallowed(@TempDir Path baseDir) {
        UUID broken = UUID.randomUUID();
        when(mapper.selectById(broken)).thenThrow(new IllegalStateException("db down"));
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        assertThatThrownBy(() -> writer.writeFile(broken, "A.java", "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }

    @Test
    void patchFileAppliesSingleHunkToExistingFile(@TempDir Path baseDir) throws Exception {
        Path file = baseDir.resolve("ws-1/README.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "line1\nline2\nline3\nline4\n");
        String patch = """
                --- a/README.md
                +++ b/README.md
                @@ -2,3 +2,3 @@
                 line2
                -line3
                +line3 changed
                 line4
                """;

        WorkspaceWriteResult result = new LocalWorkspaceCodeWriter(service(baseDir.toString()))
                .patchFile(workspaceId, "README.md", hash(file), patch);

        assertThat(result.isOk()).isTrue();
        assertThat(result.isChanged()).isTrue();
        assertThat(result.getNewSha256()).isNotNull();
        assertThat(Files.readString(file)).isEqualTo("line1\nline2\nline3 changed\nline4\n");
    }

    @Test
    void patchFileAppliesInsertThenDeletePrecisely(@TempDir Path baseDir) throws Exception {
        Path file = baseDir.resolve("ws-1/multi.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "a\nb\nc\nd\ne\nf\n");
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceWriteResult insert = writer.patchFile(workspaceId, "multi.txt", hash(file),
                "@@ -2,0 +2,1 @@\n+inserted\n");
        assertThat(insert.isOk()).isTrue();
        assertThat(insert.isChanged()).isTrue();
        assertThat(Files.readString(file)).isEqualTo("a\ninserted\nb\nc\nd\ne\nf\n");

        WorkspaceWriteResult delete = writer.patchFile(workspaceId, "multi.txt", hash(file),
                "@@ -2,1 +2,0 @@\n-inserted\n");
        assertThat(delete.isOk()).isTrue();
        // 删掉 inserted 后内容回到初始：changed=true（文件相对补丁前有变化），新哈希等于初始内容哈希。
        assertThat(delete.isChanged()).isTrue();
        assertThat(delete.getNewSha256()).isEqualTo(hash(file));
        assertThat(Files.readString(file)).isEqualTo("a\nb\nc\nd\ne\nf\n");
    }

    @Test
    void patchFileRejectsHashMismatchLeavingFileUnchanged(@TempDir Path baseDir) throws Exception {
        Path file = baseDir.resolve("ws-1/example.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "current\n");

        WorkspaceWriteResult result = new LocalWorkspaceCodeWriter(service(baseDir.toString()))
                .patchFile(workspaceId, "example.txt", "0".repeat(64),
                        "@@ -1,1 +1,1 @@\n-current\n+changed\n");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getError()).contains("changed since read");
        assertThat(result.isInfrastructureFailure()).isFalse();
        assertThat(Files.readString(file)).isEqualTo("current\n");
    }

    @Test
    void patchFileRejectsContextMismatchLeavingFileUnchanged(@TempDir Path baseDir) throws Exception {
        Path file = baseDir.resolve("ws-1/mismatch.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "aaa\nbbb\nccc\n");

        WorkspaceWriteResult result = new LocalWorkspaceCodeWriter(service(baseDir.toString()))
                .patchFile(workspaceId, "mismatch.txt", hash(file),
                        "@@ -1,3 +1,3 @@\n aaa\n-xxx\n+yyy\n ccc\n");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getError()).contains("上下文与文件不一致");
        assertThat(result.isInfrastructureFailure()).isFalse();
        assertThat(Files.readString(file)).isEqualTo("aaa\nbbb\nccc\n");
    }

    @Test
    void patchFileRejectsPathTraversalAndAbsolutePaths(@TempDir Path baseDir) throws Exception {
        Files.createDirectories(baseDir.resolve("ws-1"));
        Files.writeString(baseDir.resolve("ws-1/safe.txt"), "a\n");
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceWriteResult traversal = writer.patchFile(workspaceId, "../escape.txt", HASH,
                "@@ -1,1 +1,1 @@\n-a\n+b\n");
        assertThat(traversal.isOk()).isFalse();
        assertThat(traversal.getError()).contains("escape");
        assertThat(Files.exists(baseDir.resolve("escape.txt"))).isFalse();

        String absolute = Path.of(".").toAbsolutePath().resolve("evil.txt").toString();
        WorkspaceWriteResult abs = writer.patchFile(workspaceId, absolute, HASH,
                "@@ -1,1 +1,1 @@\n-a\n+b\n");
        assertThat(abs.isOk()).isFalse();
        assertThat(Files.exists(Path.of(absolute))).isFalse();
    }

    @Test
    void patchFileRejectsMissingDirectoryAndNonUtf8Targets(@TempDir Path baseDir) throws Exception {
        Files.createDirectories(baseDir.resolve("ws-1/docs"));
        Path binary = baseDir.resolve("ws-1/binary.dat");
        byte[] bytes = {(byte) 0xFF, (byte) 0xFE, 'a', '\n'};
        Files.write(binary, bytes);
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceWriteResult missing = writer.patchFile(workspaceId, "missing.txt", HASH,
                "@@ -1,1 +1,1 @@\n-a\n+b\n");
        assertThat(missing.isOk()).isFalse();
        assertThat(missing.getError()).contains("regular file");

        WorkspaceWriteResult directory = writer.patchFile(workspaceId, "docs", HASH,
                "@@ -1,1 +1,1 @@\n-a\n+b\n");
        assertThat(directory.isOk()).isFalse();
        assertThat(directory.getError()).contains("regular file");

        WorkspaceWriteResult nonUtf8 = writer.patchFile(workspaceId, "binary.dat", hash(binary),
                "@@ -1,1 +1,1 @@\n-a\n+b\n");
        assertThat(nonUtf8.isOk()).isFalse();
        assertThat(nonUtf8.getError()).contains("not UTF-8");
        assertThat(Files.readAllBytes(binary)).containsExactly(bytes);
    }

    @Test
    void patchFileRejectsSymlinkTarget(@TempDir Path baseDir) throws Exception {
        Files.createDirectories(baseDir.resolve("ws-1"));
        Path outside = baseDir.resolve("outside.txt");
        Files.writeString(outside, "outside\n");
        try {
            Files.createSymbolicLink(baseDir.resolve("ws-1/link.txt"), outside);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink not supported on this platform");
        }
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceWriteResult result = writer.patchFile(workspaceId, "link.txt", hash(outside),
                "@@ -1,1 +1,1 @@\n-outside\n+changed\n");

        assertThat(result.isOk()).isFalse();
        assertThat(result.getError()).contains("regular file");
        assertThat(Files.readString(outside)).isEqualTo("outside\n");
    }

    @Test
    void patchFileRejectsParentDirectorySymlinkEscape(@TempDir Path baseDir) throws Exception {
        Files.createDirectories(baseDir.resolve("ws-1"));
        Path outsideDir = Files.createDirectories(baseDir.resolve("outside"));
        Path victim = outsideDir.resolve("victim.txt");
        Files.writeString(victim, "original\n");
        try {
            Files.createSymbolicLink(baseDir.resolve("ws-1/sub"), outsideDir);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlink not supported on this platform");
        }
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceWriteResult result = writer.patchFile(workspaceId, "sub/victim.txt", hash(victim),
                "@@ -1,1 +1,1 @@\n-original\n+changed\n");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isFalse();
        assertThat(result.getError()).contains("escape");
        assertThat(Files.readString(victim)).isEqualTo("original\n");
    }

    @Test
    void patchFileRejectsInvalidPathCharactersAsToolError(@TempDir Path baseDir) {
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));

        WorkspaceWriteResult result = writer.patchFile(workspaceId, "bad\0path.txt", HASH,
                "@@ -1,1 +1,1 @@\n-a\n+b\n");

        assertThat(result.isOk()).isFalse();
        assertThat(result.isInfrastructureFailure()).isFalse();
        assertThat(result.getError()).contains("invalid characters");
    }

    @Test
    void patchFileRejectsBlankPatchInvalidHashAndOversize(@TempDir Path baseDir) {
        LocalWorkspaceCodeWriter writer = new LocalWorkspaceCodeWriter(service(baseDir.toString()));
        String patch = "@@ -1,1 +1,1 @@\n-a\n+b\n";

        assertThat(writer.patchFile(workspaceId, "A.java", "not-a-hash", patch).isOk()).isFalse();
        assertThat(writer.patchFile(workspaceId, "A.java", HASH, "  ").isOk()).isFalse();
        assertThat(writer.patchFile(workspaceId, "A.java", HASH, "x".repeat(1024 * 1024 + 1)).isOk()).isFalse();
        assertThat(writer.patchFile(workspaceId, "  ", HASH, patch).isOk()).isFalse();
    }

    private String hash(Path file) throws IOException {
        return Sha256.hex(Files.readAllBytes(file));
    }
}
