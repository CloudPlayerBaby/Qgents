package qg.qgent.orchestration.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.service.WorkspaceService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LocalWorkspaceCodeAccess 单元测试：基于 {@code @TempDir} 的真实本地目录 + Mock Mapper，
 * 覆盖正常列目录/读取/检索、目录未就绪、路径越界拒绝、超大文件拒绝，以及 Workspace
 * 服务异常向上传播。不写任何 Secret。
 */
class LocalWorkspaceCodeAccessTest {

    private static final int MAX_READ_BYTES = 64 * 1024;

    private final WorkspaceMapper mapper = mock(WorkspaceMapper.class);
    private final UUID workspaceId = UUID.randomUUID();
    /** 每个测试方法共享的临时 base-dir，setUp 与测试体使用同一目录。 */
    @TempDir
    Path baseDir;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setStorageKey("ws-1");
        when(mapper.selectById(workspaceId)).thenReturn(workspace);
        root = baseDir.resolve("ws-1");
        Files.createDirectories(root);
    }

    private LocalWorkspaceCodeAccess access() {
        return new LocalWorkspaceCodeAccess(new WorkspaceService(mapper, baseDir.toString()));
    }

    @Test
    void listFilesReturnsSortedRelativeCodePathsAndSkipsBuildDirs() throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/X.java"), "class X {}");
        Files.writeString(root.resolve("README.md"), "readme");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/out.class"), "bytes");
        Files.writeString(root.resolve(".hidden"), "secret file");

        List<String> files = access().listFiles(workspaceId);

        assertThat(files).containsExactly("README.md", "src/main/java/X.java");
    }

    @Test
    void readFileReturnsUtf8Content() throws Exception {
        Files.writeString(root.resolve("A.java"), "public class A {}");

        assertThat(access().readFile(workspaceId, "A.java")).isEqualTo("public class A {}");
    }

    @Test
    void readFileReturnsNullForMissingOrNonRegularFile() {
        LocalWorkspaceCodeAccess access = access();

        assertThat(access.readFile(workspaceId, "Missing.java")).isNull();
        assertThat(access.readFile(workspaceId, "some/dir")).isNull();
    }

    @Test
    void readFileReturnsNullForOversizedFile() throws Exception {
        Files.writeString(root.resolve("big.java"), "x".repeat(MAX_READ_BYTES + 1));

        assertThat(access().readFile(workspaceId, "big.java")).isNull();
    }

    @Test
    void searchCodeFindsFilesIgnoreCaseAndSkipsNonMatches() throws Exception {
        Files.writeString(root.resolve("A.java"), "public class Car {}");
        Files.writeString(root.resolve("B.java"), "nothing here");
        Files.writeString(root.resolve("c.md"), "plain documentation");

        LocalWorkspaceCodeAccess access = access();

        assertThat(access.searchCode(workspaceId, "car")).containsExactly("A.java");
        assertThat(access.searchCode(workspaceId, "notfound")).isEmpty();
        assertThat(access.searchCode(workspaceId, "  ")).isEmpty();
    }

    @Test
    void missingWorkspaceDirectoryReturnsEmptyNotFabricatedContent() {
        WorkspaceEntity absent = new WorkspaceEntity();
        absent.setId(UUID.randomUUID());
        absent.setStorageKey("absent");
        when(mapper.selectById(absent.getId())).thenReturn(absent);
        LocalWorkspaceCodeAccess access = access();

        assertThat(access.listFiles(absent.getId())).isEmpty();
        assertThat(access.readFile(absent.getId(), "A.java")).isNull();
        assertThat(access.searchCode(absent.getId(), "x")).isEmpty();
    }

    @Test
    void readFileRejectsPathTraversal() throws Exception {
        Files.writeString(baseDir.resolve("escape.txt"), "evil");
        LocalWorkspaceCodeAccess access = access();

        assertThat(access.readFile(workspaceId, "../escape.txt")).isNull();
        assertThat(access.readFile(workspaceId, "sub/../../escape.txt")).isNull();
    }

    @Test
    void readFileRejectsAbsolutePathOutsideRoot() throws Exception {
        String absolute = Path.of(".").toAbsolutePath().resolve("evil.txt").toString();
        LocalWorkspaceCodeAccess access = access();

        assertThat(access.readFile(workspaceId, absolute)).isNull();
    }

    @Test
    void unknownWorkspaceReturnsEmpty() {
        UUID unknown = UUID.randomUUID();
        when(mapper.selectById(unknown)).thenReturn(null);
        LocalWorkspaceCodeAccess access = access();

        assertThat(access.listFiles(unknown)).isEmpty();
        assertThat(access.readFile(unknown, "A.java")).isNull();
    }

    @Test
    void serviceFailurePropagatesNotSwallowed() {
        UUID broken = UUID.randomUUID();
        when(mapper.selectById(broken)).thenThrow(new IllegalStateException("db down"));
        LocalWorkspaceCodeAccess access = access();

        assertThatThrownBy(() -> access.listFiles(broken)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
        assertThatThrownBy(() -> access.readFile(broken, "A.java")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }
}
