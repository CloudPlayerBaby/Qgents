package qg.qgent.orchestration.tool;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.mapper.WorkspaceMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * WorkspaceCodeWriter 的本地最小实现，安全约束即本类的核心：
 * <ul>
 *   <li>路径归一化后必须仍位于 Workspace 根目录内，拒绝绝对路径与 {@code ..} 越界（防路径穿越）；</li>
 *   <li>workspace 根目录不可解析（未配置 base-dir 或 Workspace 不存在）时直接拒绝；</li>
 *   <li>单文件内容上限 256KB，拒绝超大写入；</li>
 *   <li>父目录不存在时按约定自动创建（mkdirs）；</li>
 *   <li>写入失败返回明确错误，绝不静默丢弃。</li>
 * </ul>
 * 真实 Sandbox 接入后由沙箱内实现替换本类，安全边界保持不变。
 */
@Component
public class LocalWorkspaceCodeWriter implements WorkspaceCodeWriter {

    /** 单次写入内容的最大字节数。 */
    private static final int MAX_WRITE_BYTES = 256 * 1024;

    private final WorkspaceMapper workspaceMapper;
    private final String baseDir;

    public LocalWorkspaceCodeWriter(WorkspaceMapper workspaceMapper,
            @Value("${app.workspace.base-dir:}") String baseDir) {
        this.workspaceMapper = workspaceMapper;
        this.baseDir = baseDir == null ? "" : baseDir;
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
            return WorkspaceWriteResult.fail(path, "workspace root is not available");
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
            return WorkspaceWriteResult.fail(path, "write failed: " + e.getMessage());
        }
    }

    /** 解析 Workspace 根目录；未配置 base-dir 或目录不可用时返回 null。 */
    private Path workspaceRoot(UUID workspaceId) {
        if (workspaceId == null || baseDir.isBlank()) {
            return null;
        }
        WorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || workspace.getStorageKey() == null) {
            return null;
        }
        return Path.of(baseDir, workspace.getStorageKey()).normalize();
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
