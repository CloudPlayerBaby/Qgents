package qg.qgent.orchestration.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.service.WorkspaceService;

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
    void writeFilePersistsContentAndCreatesParentDirs(@TempDir Path baseDir) throws Exception {
        WorkspaceWriteResult result = new LocalWorkspaceCodeWriter(service(baseDir.toString()))
                .writeFile(workspaceId, "src/main/java/Y.java", "new code");

        assertThat(result.isOk()).isTrue();
        assertThat(result.getPath()).isEqualTo("src/main/java/Y.java");
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
}
