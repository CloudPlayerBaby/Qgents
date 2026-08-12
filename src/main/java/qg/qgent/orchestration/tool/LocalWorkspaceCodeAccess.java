package qg.qgent.orchestration.tool;

import org.springframework.stereotype.Component;
import qg.qgent.service.WorkspaceService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * WorkspaceCodeAccess 的最小本地实现，供 Plan/Coding/Review Agent 在真实 Sandbox 落地前读取代码。
 * <p>
 * 工作区根目录由 {@link WorkspaceService} 统一解析（app.workspace.base-dir/{storageKey}，
 * 含存在性与越界校验）；目录未就绪时如实返回空结果，绝不返回编造的文件内容。真实 Sandbox
 * 接入后由沙箱内代码访问实现替换本类，接口与安全边界保持不变。
 */
@Component
public class LocalWorkspaceCodeAccess implements WorkspaceCodeAccess {

    /** 单文件检索/读取的最大字节数，防止把超大文件塞进 Agent 上下文。 */
    private static final long MAX_READ_BYTES = 64 * 1024;

    private final WorkspaceService workspaceService;

    public LocalWorkspaceCodeAccess(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    @Override
    public List<String> listFiles(UUID workspaceId) {
        Path root = workspaceRoot(workspaceId);
        if (root == null) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(this::isCodeFile)
                    .map(root::relativize)
                    .map(LocalWorkspaceCodeAccess::toPortablePath)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @Override
    public String readFile(UUID workspaceId, String path) {
        Path root = workspaceRoot(workspaceId);
        Path file = resolveSafe(root, path);
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            if (Files.size(file) > MAX_READ_BYTES) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public List<String> searchCode(UUID workspaceId, String query) {
        Path root = workspaceRoot(workspaceId);
        if (root == null || query == null || query.isBlank()) {
            return List.of();
        }
        String needle = query.toLowerCase(Locale.ROOT);
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(this::isCodeFile)
                    .filter(p -> containsIgnoreCase(p, needle))
                    .map(root::relativize)
                    .map(LocalWorkspaceCodeAccess::toPortablePath)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** 统一为 forward-slash 相对路径，保证跨平台往返一致（LLM 回传的路径可被任何平台解析）。 */
    private static String toPortablePath(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    /** 解析 Workspace 根目录；workspace 不可解析或目录未就绪时返回 null。 */
    private Path workspaceRoot(UUID workspaceId) {
        WorkspaceService.WorkspaceResolution resolution = workspaceService.resolve(workspaceId);
        return resolution.ready() ? resolution.root() : null;
    }

    /** 路径归一化并校验仍在根目录内，防止目录穿越。 */
    private Path resolveSafe(Path root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        Path resolved = root.resolve(path).normalize();
        return resolved.startsWith(root) ? resolved : null;
    }

    private boolean isCodeFile(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith(".")) {
            return false;
        }
        String p = path.toString().replace('\\', '/');
        return !p.contains("/target/") && !p.contains("/.git/") && !p.contains("/node_modules/")
                && !p.contains("/.idea/") && !p.contains("/build/");
    }

    private boolean containsIgnoreCase(Path path, String needle) {
        try {
            if (Files.size(path) > MAX_READ_BYTES) {
                return false;
            }
            return Files.readString(path, StandardCharsets.UTF_8)
                    .toLowerCase(Locale.ROOT)
                    .contains(needle);
        } catch (IOException e) {
            return false;
        }
    }
}
