package qg.qgent.orchestration.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;
import qg.qgent.service.TaskRunWorkerExecutionService;

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

    /** 兼容无 Spring 容器的端口单元测试；生产装配使用带诊断持久化服务的构造器。 */
    public WorkerWorkspaceCodeAccess(SandboxWorkerClient client, SandboxSessionManager sessions,
                                     SandboxWorkerProperties properties) {
        super(client, sessions, properties);
    }

    @Autowired
    public WorkerWorkspaceCodeAccess(SandboxWorkerClient client, SandboxSessionManager sessions,
                                     SandboxWorkerProperties properties,
                                     TaskRunWorkerExecutionService workerExecutionService) {
        super(client, sessions, properties, workerExecutionService);
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
    public WorkspaceFileReadResult readFile(UUID workspaceId, String path) {
        WorkerPathResolver.Target target = WorkerPathResolver.resolve(session(workspaceId), path);
        if (target == null) {
            return WorkspaceFileReadResult.fail(path, "path does not map to a workspace repository");
        }
        String sha256 = null;
        List<String> allLines = new ArrayList<>();
        int startLine = 1;
        while (true) {
            WorkerToolExecution execution = executeTool(workspaceId, target.repositoryId(), "file.read",
                    Map.of("path", target.relativePath(), "startLine", startLine, "lineCount", READ_PAGE_LINES),
                    TOOL_TIMEOUT);
            if (!"SUCCEEDED".equals(execution.getStatus())) {
                return WorkspaceFileReadResult.fail(path,
                        execution.getFailureReason() == null ? "read failed" : execution.getFailureReason());
            }
            Map<String, Object> result = resultOf(execution);
            if (sha256 == null) {
                Object sha = result.get("sha256");
                sha256 = sha == null ? null : String.valueOf(sha);
            }
            Object lines = result.get("lines");
            if (!(lines instanceof List<?> page)) {
                return WorkspaceFileReadResult.fail(path, "read returned no lines");
            }
            for (Object line : page) {
                allLines.add(String.valueOf(line));
            }
            if (!Boolean.TRUE.equals(result.get("truncated"))) {
                break;
            }
            startLine += READ_PAGE_LINES;
            if (allLines.size() > 100_000) {
                return WorkspaceFileReadResult.fail(path, "file too large to read");
            }
        }
        String content = String.join("\n", allLines);
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_READ_BYTES) {
            return WorkspaceFileReadResult.fail(path, "file exceeds 64KB read limit");
        }
        return WorkspaceFileReadResult.ok(path, content, sha256);
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
     * <p>
     * 空目录也会被返回（目录本身作为一条路径）：Git 不跟踪空目录，Reviewer 的文件清单
     * 和 Coding 的目标判定若只依赖「目录下有文件」就无法感知已创建的空目录，导致误判
     * REVIEW_ASSERTION_TARGET_NOT_FOUND。把空目录自身返回后，目标判定
     * （精确命中或作为目录前缀存在）即可识别目录已创建。
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
                List<String> nested = listRecursive(workspaceId, repositoryId, child);
                if (nested.isEmpty()) {
                    // 空目录：没有可跟踪文件，目录自身作为路径返回，供目标判定/文件清单感知。
                    files.add(child);
                } else {
                    files.addAll(nested);
                }
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
