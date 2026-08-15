package qg.qgent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.mapper.WorkspaceMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Workspace 只读解析服务：把 workspaceId 映射到本地工作区根目录，并给出就绪状态。
 * <p>
 * 本地存储约定：代码位于 {@code app.workspace.base-dir}/{workspace.storageKey}。
 * 归属边界：本服务只校验 workspace 存在与 storageKey 合法；workspace 与 Task/Project
 * 的归属关系在编排入口（Task 创建/复用校验）与服务端授权层完成，Agent 工具调用只携带
 * workspaceId，不在本层重复校验 projectId。
 * <p>
 * 安全约束：storageKey 视为外部输入，解析后必须仍位于 base-dir 内，禁止 {@code ..} 越界；
 * 目录不存在时如实返回 NOT_READY，绝不编造文件内容。本服务不吞异常：Mapper 或文件系统
 * 抛出的运行时异常向上传播，由调用方（Adapter → Agent）映射为基础设施失败，不得伪装成通过。
 */
@Service
public class WorkspaceService {

    private final WorkspaceMapper workspaceMapper;
    private final Path base;

    public WorkspaceService(WorkspaceMapper workspaceMapper,
                            @Value("${app.workspace.base-dir:}") String baseDir) {
        this.workspaceMapper = workspaceMapper;
        this.base = (baseDir == null || baseDir.isBlank()) ? null
                : Path.of(baseDir).toAbsolutePath().normalize();
    }

    /**
     * 解析 workspaceId → 本地工作区根目录与就绪状态。
     *
     * @param workspaceId 目标 Workspace。
     * @return 解析结果：found 表示 workspace 存在且路径合法可解析；
     * ready 表示对应目录已存在（found 为 true 时才有意义）；
     * root 为解析出的根目录（found 为 true 时非 null）；
     * reason 为不可用原因（正常就绪时为 null）。
     */
    public WorkspaceResolution resolve(UUID workspaceId) {
        if (base == null) {
            return WorkspaceResolution.notConfigured();
        }
        if (workspaceId == null) {
            return WorkspaceResolution.invalid("workspaceId must not be null");
        }
        WorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || workspace.getStorageKey() == null || workspace.getStorageKey().isBlank()) {
            return WorkspaceResolution.notFound(workspaceId);
        }
        Path root = base.resolve(workspace.getStorageKey()).normalize();
        if (!root.startsWith(base)) {
            return WorkspaceResolution.invalid("workspace storageKey escapes base-dir");
        }
        boolean ready = Files.isDirectory(root);
        return WorkspaceResolution.resolved(root, ready, ready ? null : "workspace directory not present: " + root);
    }

    /**
     * workspaceId 解析结果（内部值对象）。
     */
    public record WorkspaceResolution(boolean found, boolean ready, Path root, String reason) {

        private static WorkspaceResolution notConfigured() {
            return new WorkspaceResolution(false, false, null, "workspace base-dir is not configured");
        }

        private static WorkspaceResolution notFound(UUID workspaceId) {
            return new WorkspaceResolution(false, false, null, "workspace not found: " + workspaceId);
        }

        private static WorkspaceResolution invalid(String reason) {
            return new WorkspaceResolution(false, false, null, reason);
        }

        private static WorkspaceResolution resolved(Path root, boolean ready, String reason) {
            return new WorkspaceResolution(true, ready, root, reason);
        }
    }
}
