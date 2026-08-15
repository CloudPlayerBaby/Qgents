package qg.qgent.orchestration.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * {@link WorkspaceCodeAccess} 的 Worker 实现：通过 Worker 的 {@code file.list/file.read/file.search}
 * 工具读取代码，替代主后端本地文件系统访问。
 * <p>
 * 形状翻译：Worker 的 file.list 是一级子项、file.read 是分页 lines、file.search 是 ripgrep
 * 原始输出，本类把它们还原为端口契约要求的"递归相对路径列表 / 整段文本 / 命中路径列表"，
 * 并对 Worker 的目录树同样过滤 .git/target 等噪声目录。
 * <p>
 * {@code app.worker.enabled=true} 时启用本实现，{@link qg.qgent.orchestration.tool.LocalWorkspaceCodeAccess}
 * 随之停用。
 */
@Component
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class WorkerWorkspaceCodeAccess extends AbstractWorkerToolPort implements WorkspaceCodeAccess {

    /**
     * 单文件检索/读取的最大字节数，防止把超大文件塞进 Agent 上下文。
     */
    private static final int MAX_READ_BYTES = 64 * 1024;
    /**
     * 单次 file.read 每页行数。
     */
    private static final int READ_PAGE_LINES = 1000;
    /**
     * 单次 file.list / file.read / file.search 工具超时。
     */
    private static final Duration TOOL_TIMEOUT = Duration.ofSeconds(30);

    public WorkerWorkspaceCodeAccess(SandboxWorkerClient client, SandboxSessionManager sessions,
                                     SandboxWorkerProperties properties) {
        super(client, sessions, properties);
    }

    @Override
    public List<String> listFiles(UUID workspaceId) {
        SandboxSession session = session(workspaceId);
        List<String> files = new ArrayList<>();
        for (Map.Entry<String, UUID> entry : session.repositoryByPath().entrySet()) {
            String prefix = entry.getKey();
            for (String relative : listRecursive(workspaceId, entry.getValue(), ".")) {
                files.add(prefix + "/" + relative);
            }
        }
        files.sort(String::compareTo);
        return files;
    }

    @Override
    public String readFile(UUID workspaceId, String path) {
        WorkerPathResolver.Target target = WorkerPathResolver.resolve(session(workspaceId), path);
        if (target == null) {
            return null;
        }
        List<String> allLines = new ArrayList<>();
        int startLine = 1;
        while (true) {
            WorkerToolExecution execution = executeTool(workspaceId, target.repositoryId(), "file.read",
                    Map.of("path", target.relativePath(), "startLine", startLine, "lineCount", READ_PAGE_LINES),
                    TOOL_TIMEOUT);
            if (!"SUCCEEDED".equals(execution.getStatus())) {
                return null;
            }
            Object lines = resultOf(execution).get("lines");
            if (!(lines instanceof List<?> page)) {
                return null;
            }
            for (Object line : page) {
                allLines.add(String.valueOf(line));
            }
            if (!Boolean.TRUE.equals(resultOf(execution).get("truncated"))) {
                break;
            }
            startLine += READ_PAGE_LINES;
            if (allLines.size() > 100_000) {
                return null;
            }
        }
        String content = String.join("\n", allLines);
        return content.getBytes(StandardCharsets.UTF_8).length > MAX_READ_BYTES ? null : content;
    }

    @Override
    public List<String> searchCode(UUID workspaceId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        SandboxSession session = session(workspaceId);
        TreeSet<String> matches = new TreeSet<>();
        for (Map.Entry<String, UUID> entry : session.repositoryByPath().entrySet()) {
            WorkerToolExecution execution = executeTool(workspaceId, entry.getValue(), "file.search",
                    Map.of("query", query, "path", "."), TOOL_TIMEOUT);
            if (!"SUCCEEDED".equals(execution.getStatus())) {
                continue;
            }
            Object raw = resultOf(execution).get("matches");
            if (!(raw instanceof List<?> lines)) {
                continue;
            }
            for (Object line : lines) {
                String text = String.valueOf(line);
                int colon = text.indexOf(':');
                if (colon > 0) {
                    matches.add(entry.getKey() + "/" + text.substring(0, colon));
                }
            }
        }
        return List.copyOf(matches);
    }

    /**
     * 递归列出仓库内相对路径，跳过 .git/target/node_modules/.idea/build 与点文件。
     */
    private List<String> listRecursive(UUID workspaceId, UUID repositoryId, String dir) {
        List<String> files = new ArrayList<>();
        WorkerToolExecution execution = executeTool(workspaceId, repositoryId, "file.list",
                Map.of("path", dir), TOOL_TIMEOUT);
        if (!"SUCCEEDED".equals(execution.getStatus())) {
            return files;
        }
        Object items = resultOf(execution).get("items");
        if (!(items instanceof List<?> list)) {
            return files;
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> entry)) {
                continue;
            }
            String name = entry.get("name") == null ? null : String.valueOf(entry.get("name"));
            if (name == null || name.startsWith(".")) {
                continue;
            }
            boolean directory = Boolean.TRUE.equals(entry.get("directory"));
            String child = ".".equals(dir) ? name : dir + "/" + name;
            if (directory) {
                if (isIgnoredDirectory(name)) {
                    continue;
                }
                files.addAll(listRecursive(workspaceId, repositoryId, child));
            } else {
                files.add(child);
            }
        }
        return files;
    }

    private static boolean isIgnoredDirectory(String name) {
        return "target".equals(name) || "node_modules".equals(name) || ".idea".equals(name)
                || "build".equals(name) || ".git".equals(name);
    }
}
