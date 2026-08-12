package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.mapper.WorkspaceMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WorkspaceService 纯单元测试：校验 workspaceId → 本地根目录的解析与就绪判定，
 * 覆盖就绪/未就绪/不存在/未配置/路径越界/参数为空，以及 Mapper 异常向上传播。
 * 使用 {@code @TempDir} 作为 base-dir，不写任何 Secret。
 */
class WorkspaceServiceTest {

    private final WorkspaceMapper mapper = mock(WorkspaceMapper.class);

    private WorkspaceEntity workspace(String storageKey) {
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(UUID.randomUUID());
        workspace.setStorageKey(storageKey);
        return workspace;
    }

    @Test
    void resolvesExistingReadyWorkspace(@TempDir Path baseDir) throws Exception {
        WorkspaceEntity workspace = workspace("ws-1");
        when(mapper.selectById(workspace.getId())).thenReturn(workspace);
        Files.createDirectories(baseDir.resolve("ws-1"));

        WorkspaceService.WorkspaceResolution r = new WorkspaceService(mapper, baseDir.toString()).resolve(workspace.getId());

        assertThat(r.found()).isTrue();
        assertThat(r.ready()).isTrue();
        assertThat(r.root()).isEqualTo(baseDir.resolve("ws-1").normalize());
        assertThat(r.reason()).isNull();
    }

    @Test
    void existingWorkspaceWithoutDirectoryIsFoundButNotReady(@TempDir Path baseDir) {
        WorkspaceEntity workspace = workspace("ws-1");
        when(mapper.selectById(workspace.getId())).thenReturn(workspace);

        WorkspaceService.WorkspaceResolution r = new WorkspaceService(mapper, baseDir.toString()).resolve(workspace.getId());

        assertThat(r.found()).isTrue();
        assertThat(r.ready()).isFalse();
        assertThat(r.root()).isEqualTo(baseDir.resolve("ws-1").normalize());
        assertThat(r.reason()).contains("not present");
    }

    @Test
    void unknownWorkspaceIsNotFound(@TempDir Path baseDir) {
        UUID workspaceId = UUID.randomUUID();
        when(mapper.selectById(workspaceId)).thenReturn(null);

        WorkspaceService.WorkspaceResolution r = new WorkspaceService(mapper, baseDir.toString()).resolve(workspaceId);

        assertThat(r.found()).isFalse();
        assertThat(r.ready()).isFalse();
        assertThat(r.root()).isNull();
        assertThat(r.reason()).contains("not found");
    }

    @Test
    void blankStorageKeyIsNotFound(@TempDir Path baseDir) {
        WorkspaceEntity workspace = workspace("  ");
        when(mapper.selectById(workspace.getId())).thenReturn(workspace);

        WorkspaceService.WorkspaceResolution r = new WorkspaceService(mapper, baseDir.toString()).resolve(workspace.getId());

        assertThat(r.found()).isFalse();
        assertThat(r.root()).isNull();
        assertThat(r.reason()).contains("not found");
    }

    @Test
    void nullWorkspaceIdIsRejected(@TempDir Path baseDir) {
        WorkspaceService.WorkspaceResolution r = new WorkspaceService(mapper, baseDir.toString()).resolve(null);

        assertThat(r.found()).isFalse();
        assertThat(r.ready()).isFalse();
        assertThat(r.root()).isNull();
        assertThat(r.reason()).contains("workspaceId");
    }

    @Test
    void unconfiguredBaseDirIsNotConfigured() {
        WorkspaceEntity workspace = workspace("ws-1");

        WorkspaceService.WorkspaceResolution r = new WorkspaceService(mapper, "").resolve(workspace.getId());

        assertThat(r.found()).isFalse();
        assertThat(r.root()).isNull();
        assertThat(r.reason()).contains("base-dir");
    }

    @Test
    void storageKeyEscapingBaseDirIsRejected(@TempDir Path baseDir) {
        WorkspaceEntity workspace = workspace("../escape");
        when(mapper.selectById(workspace.getId())).thenReturn(workspace);

        WorkspaceService.WorkspaceResolution r = new WorkspaceService(mapper, baseDir.toString()).resolve(workspace.getId());

        assertThat(r.found()).isFalse();
        assertThat(r.ready()).isFalse();
        assertThat(r.root()).isNull();
        assertThat(r.reason()).contains("escape");
    }

    @Test
    void mapperFailurePropagatesToCaller(@TempDir Path baseDir) {
        UUID workspaceId = UUID.randomUUID();
        when(mapper.selectById(workspaceId)).thenThrow(new IllegalStateException("db down"));

        WorkspaceService service = new WorkspaceService(mapper, baseDir.toString());

        assertThatThrownBy(() -> service.resolve(workspaceId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }
}
