package qg.qgent.orchestration.tool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.service.WorkspaceService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * WorkspaceCodeWriter 的本地最小实现，安全约束即本类的核心：
 * <ul>
 *   <li>工作区根目录由 {@link WorkspaceService} 统一解析（app.workspace.base-dir/{storageKey}），
 *       只接受 found 的 workspace，未配置 base-dir 或 Workspace 不存在时直接拒绝；</li>
 *   <li>路径归一化后必须仍位于 Workspace 根目录内，拒绝绝对路径与 {@code ..} 越界（防路径穿越）；</li>
 *   <li>单文件内容上限 256KB，拒绝超大写入；</li>
 *   <li>父目录不存在时按约定自动创建（mkdirs）；</li>
 *   <li>写入失败返回明确错误，绝不静默丢弃。</li>
 *   <li>workspace 不可解析或文件系统写入异常属于基础设施失败（{@link WorkspaceWriteResult#infraFail}），
 *       应由 Agent 映射 FAILED_INFRASTRUCTURE；参数/路径/大小错误属于工具级失败，可回灌模型纠正。</li>
 * </ul>
 * 真实 Sandbox 接入后由沙箱内实现替换本类，安全边界保持不变。
 */
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "false", matchIfMissing = true)
public class LocalWorkspaceCodeWriter implements WorkspaceCodeWriter {

    /** 单次写入内容的最大字节数。 */
    private static final int MAX_WRITE_BYTES = 256 * 1024;

    private final WorkspaceService workspaceService;

    public LocalWorkspaceCodeWriter(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Override
    public WorkspaceWriteResult writeFile(UUID workspaceId, String path, String content) {
        if (path == null || path.isBlank()) {
            return WorkspaceWriteResult.fail(null, "path must not be blank");
        }
        if (content == null) {
            return WorkspaceWriteResult.fail(path, "content must not be null");
        }
        Path root = workspaceRoot(workspaceId);
        if (root == null) {
            return WorkspaceWriteResult.infraFail(path, "workspace root is not available");
        }
        Path target = resolveSafe(root, path);
        if (target == null) {
            return WorkspaceWriteResult.fail(path, "path escapes workspace root or is absolute");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_WRITE_BYTES) {
            return WorkspaceWriteResult.fail(path, "content exceeds 256KB limit");
        }
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
            return WorkspaceWriteResult.ok(path);
        } catch (IOException e) {
            return WorkspaceWriteResult.infraFail(path, "write failed: " + e.getMessage());
        }
    }

    /** 解析 Workspace 根目录；workspace 不存在或不可解析时返回 null（目录允许尚未创建）。 */
    private Path workspaceRoot(UUID workspaceId) {
        WorkspaceService.WorkspaceResolution resolution = workspaceService.resolve(workspaceId);
        return resolution.found() ? resolution.root() : null;
    }

    /** 路径归一化并校验仍在根目录内，拒绝绝对路径与目录穿越。 */
    private Path resolveSafe(Path root, String path) {
        Path candidate = Path.of(path);
        if (candidate.isAbsolute()) {
            return null;
        }
        Path resolved = root.resolve(path).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }
}
