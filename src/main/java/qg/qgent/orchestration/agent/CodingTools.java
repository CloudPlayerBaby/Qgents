package qg.qgent.orchestration.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;
import qg.qgent.orchestration.tool.WorkspaceInfraException;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Coding Agent 的白名单工具（阶段 B 原生 Tool Calling）：以 Spring AI {@link Tool} 注解声明
 * 类型化参数，由 {@code ToolCallbacks.from(this)} 解析为原生函数 schema。每次 run 按
 * workspaceId 新建实例，工具方法签名保持干净。
 * <p>
 * 失败语义与 legacy 协议一致：
 * <ul>
 *   <li>工具级失败（参数缺失、路径越界、hash 冲突、补丁上下文不匹配等）返回 {@code ok=false}
 *       结构化结果，回灌模型自行纠正；</li>
 *   <li>基础设施级失败（workspace 不可用、文件系统错误）抛 {@link WorkspaceInfraException}，
 *       由原生工具调用层识别后中止循环，映射 FAILED_INFRASTRUCTURE，不进入模型纠正循环；</li>
 *   <li>结果只回传最小元数据（ok/path/sha256/oldSha/newSha/changed/matches/error），绝不回塞
 *       完整 patch 或文件内容（read_file 的内容除外，供模型理解现状）；</li>
 *   <li>上下文压缩：本实例按 path 维护最近一次确认的 sha256（read/write 结果），后续对同一文件
 *       再次 apply_patch 可省略 expectedHash，避免模型重复 read_file 全文回灌。</li>
 * </ul>
 * 工具结果字符串不携带 Secret；error 不含宿主机绝对路径。
 */
@Slf4j
public class CodingTools {

    private final UUID workspaceId;
    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;
    /**
     * 已确认的文件最新 sha256 快照（read_file / 成功写后更新），用于省略重复读取。
     */
    private final Map<String, String> latestSha256 = new HashMap<>();
    /**
     * 本次 run 内成功写入的文件路径（write_file / apply_patch 成功时记录，read_file 不记），
     * 供执行结束后回填 CodingResult.modifiedFiles（Verify/Review 上下文可见本次修改范围）。
     */
    private final Set<String> modifiedFiles = new HashSet<>();
    /**
     * 成功写后的预览回调（阶段 D）；null 表示未启用预览记录。由 CodingAgent 按 run 注入。
     */
    private CodingWriteObserver writeObserver;
    private UUID projectId;
    private UUID taskId;
    private UUID taskRunId;

    public CodingTools(UUID workspaceId, WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer) {
        this.workspaceId = workspaceId;
        this.codeAccess = codeAccess;
        this.writer = writer;
    }

    /**
     * 绑定成功写后的预览回调与任务上下文（projectId/taskId/taskRunId 来自 AgentInput）。
     * 由 CodingAgent 每次 run 调用；未配置（observer 为 null）时写操作不记录预览。
     */
    public void setWriteObserver(CodingWriteObserver observer, UUID projectId, UUID taskId, UUID taskRunId) {
        this.writeObserver = observer;
        this.projectId = projectId;
        this.taskId = taskId;
        this.taskRunId = taskRunId;
    }

    /**
     * 本次 run 内成功写入的文件路径（不可变快照）；仅 write_file / apply_patch 成功时记录。
     */
    public Set<String> getModifiedFiles() {
        return java.util.Collections.unmodifiableSet(modifiedFiles);
    }

    @Tool(name = "list_files", description = "列出工作区所有代码文件的相对路径，无参数")
    public Map<String, Object> listFiles() {
        List<String> files = codeAccess.listFiles(workspaceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("files", files);
        return result;
    }

    @Tool(name = "read_file", description = "读取文件的文本内容与当前 sha256（超过 64KB 会被拒绝，先 search_code 再按需读取）")
    public Map<String, Object> readFile(@ToolParam(description = "工作区内的相对路径") String path) {
        if (path == null || path.isBlank()) {
            return error("read_file requires non-empty 'path'");
        }
        WorkspaceFileReadResult read = codeAccess.readFile(workspaceId, path);
        if (read == null || !read.isOk()) {
            return error(read == null || read.getError() == null
                    ? "file not found or unreadable: " + path : read.getError());
        }
        latestSha256.put(path, read.getSha256());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("path", path);
        result.put("content", read.getContent());
        result.put("sha256", read.getSha256());
        return result;
    }

    @Tool(name = "search_code", description = "在代码中检索关键字，返回命中的文件路径列表")
    public Map<String, Object> searchCode(@ToolParam(description = "检索关键字") String query) {
        if (query == null || query.isBlank()) {
            return error("search_code requires non-empty 'query'");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("matches", codeAccess.searchCode(workspaceId, query));
        return result;
    }

    @Tool(name = "apply_patch", description = "对已有文本文件精确应用统一 Diff（不能用于创建新文件）；"
            + "expectedHash 可省略——省略时使用本会话已确认的最新哈希，否则先 read_file 获取")
    public Map<String, Object> applyPatch(
            @ToolParam(description = "工作区内的相对路径") String path,
            @ToolParam(description = "可选：期望的当前文件 64 位十六进制 sha256，省略则用已确认的最新哈希") String expectedHash,
            @ToolParam(description = "统一 Diff 文本") String patch) {
        if (path == null || path.isBlank()) {
            return error("apply_patch requires non-empty 'path'");
        }
        if (expectedHash == null || expectedHash.isBlank()) {
            expectedHash = latestSha256.get(path);
            if (expectedHash == null) {
                return error("apply_patch: no known hash for '" + path
                        + "'; read_file first to obtain current sha256");
            }
        }
        if (!expectedHash.matches("[0-9a-fA-F]{64}")) {
            return error("apply_patch requires 64-char hex 'expectedHash' from read_file");
        }
        if (patch == null || patch.isBlank()) {
            return error("apply_patch requires non-empty 'patch'");
        }
        WorkspaceWriteResult result = writer.patchFile(workspaceId, path, expectedHash, patch);
        if (result.isOk()) {
            latestSha256.put(path, result.getNewSha256());
            modifiedFiles.add(path);
            notifyWrite(result);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("path", path);
            ok.put("changed", result.isChanged());
            ok.put("oldSha", expectedHash);
            ok.put("newSha", result.getNewSha256());
            return ok;
        }
        if (result.isInfrastructureFailure()) {
            throw new WorkspaceInfraException(
                    "apply_patch infrastructure failure: " + (result.getError() == null
                            ? "workspace unavailable" : result.getError()));
        }
        return error(result.getError() == null ? "patch failed" : result.getError());
    }

    @Tool(name = "write_file", description = "创建新文件（目标文件已存在时拒绝，改用 apply_patch；内容不得超过 256KB）")
    public Map<String, Object> writeFile(
            @ToolParam(description = "工作区内的相对路径") String path,
            @ToolParam(description = "新文件完整内容") String content) {
        if (path == null || path.isBlank()) {
            return error("write_file requires non-empty 'path'");
        }
        if (content == null) {
            return error("write_file requires non-empty 'content'");
        }
        if (exists(path)) {
            return error("write_file only creates new files; '" + path
                    + "' already exists, use apply_patch for existing files");
        }
        WorkspaceWriteResult result = writer.writeFile(workspaceId, path, content);
        if (result.isOk()) {
            latestSha256.put(path, result.getNewSha256());
            modifiedFiles.add(path);
            notifyWrite(result);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("ok", true);
            ok.put("path", path);
            ok.put("changed", result.isChanged());
            ok.put("oldSha", null);
            ok.put("newSha", result.getNewSha256());
            return ok;
        }
        if (result.isInfrastructureFailure()) {
            throw new WorkspaceInfraException(
                    "write_file infrastructure failure: " + (result.getError() == null
                            ? "workspace unavailable" : result.getError()));
        }
        return error(result.getError() == null ? "write failed" : result.getError());
    }

    /**
     * 文件树包含性探测：已存在文件（含子目录）在 list_files 结果中，禁止 write_file 覆盖。
     */
    private boolean exists(String path) {
        String normalized = path.replace('\\', '/');
        return codeAccess.listFiles(workspaceId).stream()
                .anyMatch(file -> normalized.equals(file.replace('\\', '/')));
    }

    /**
     * 成功写后通知预览回调；回调失败只记日志，绝不破坏 Coding 主循环。
     */
    private void notifyWrite(WorkspaceWriteResult result) {
        if (writeObserver == null || projectId == null) {
            return;
        }
        try {
            writeObserver.onWrite(projectId, taskId, taskRunId, workspaceId, result);
        } catch (RuntimeException e) {
            log.warn("CODING_WRITE_OBSERVER_FAILED path={} category={}", result.getPath(),
                    e.getClass().getSimpleName());
        }
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("error", message);
        return result;
    }
}
