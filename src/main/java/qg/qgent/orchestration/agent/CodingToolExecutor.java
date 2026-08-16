package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;

import java.util.UUID;

/**
 * 执行 Coding Agent 的白名单工具调用，并把结果格式化为可回灌给 LLM 的 JSON 字符串。
 * <p>
 * 只允许 list_files / read_file / search_code（只读，经 {@link WorkspaceCodeAccess}）、
 * write_file（新建或整文件替换，经 {@link WorkspaceCodeWriter}）与 apply_patch（对已有文件
 * 精确应用统一 Diff）。参数缺失、越界、文件不存在或工具级写入失败（路径/参数/大小/补丁冲突）
 * 时返回 ok=false 的结构化错误而不是抛出异常，让模型能基于错误信息自行纠正；基础设施级
 * 写入失败（workspace 不可用、文件系统错误）抛出异常，由 CodingAgent 映射
 * FAILED_INFRASTRUCTURE，不进入模型纠正循环。工具结果字符串不携带 Secret。
 */
@Slf4j
public class CodingToolExecutor {

    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 成功写后的预览回调（阶段 D）；null 表示未启用预览记录。由 CodingAgent 按 run 注入。
     */
    private CodingWriteObserver writeObserver;
    private UUID projectId;
    private UUID taskId;
    private UUID taskRunId;
    private UUID workspaceId;

    public CodingToolExecutor(WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer) {
        this.codeAccess = codeAccess;
        this.writer = writer;
    }

    /**
     * 绑定成功写后的预览回调与任务上下文。由 CodingAgent 每次 run 调用；未配置时不记录预览。
     */
    public void setWriteObserver(CodingWriteObserver observer, UUID projectId, UUID taskId,
                                 UUID taskRunId, UUID workspaceId) {
        this.writeObserver = observer;
        this.projectId = projectId;
        this.taskId = taskId;
        this.taskRunId = taskRunId;
        this.workspaceId = workspaceId;
    }

    /**
     * 执行一次工具调用并返回 JSON 结果字符串。
     *
     * @param workspaceId 目标 Workspace。
     * @param toolCall    LLM 输出中的 toolCall 节点，须含 name 与可选的 arguments。
     * @return 结构化结果 JSON，如 {@code {"tool":"read_file","ok":true,"result":{...}}}。
     */
    public String execute(UUID workspaceId, JsonNode toolCall) {
        String name = toolCall.path("name").asText("").trim();
        if (name.isBlank()) {
            return error("", "toolCall missing required field 'name'");
        }
        JsonNode args = toolCall.get("arguments");
        args = args != null && args.isObject() ? args : objectMapper.createObjectNode();
        return switch (name) {
            case "list_files" -> listFiles(workspaceId, name);
            case "read_file" -> readFile(workspaceId, name, args);
            case "search_code" -> searchCode(workspaceId, name, args);
            case "write_file" -> writeFile(workspaceId, name, args);
            case "apply_patch" -> applyPatch(workspaceId, name, args);
            default -> error(name, "unknown tool '" + name + "'");
        };
    }

    private String listFiles(UUID workspaceId, String name) {
        ArrayNode files = objectMapper.createArrayNode();
        for (String path : codeAccess.listFiles(workspaceId)) {
            files.add(path);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("files", files);
        return ok(name, result);
    }

    private String readFile(UUID workspaceId, String name, JsonNode args) {
        String path = args.path("path").asText("").trim();
        if (path.isBlank()) {
            return error(name, "read_file requires non-empty 'path'");
        }
        WorkspaceFileReadResult read = codeAccess.readFile(workspaceId, path);
        if (read == null || !read.isOk()) {
            return error(name, read == null || read.getError() == null
                    ? "file not found or unreadable: " + path : read.getError());
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", path);
        result.put("content", read.getContent());
        result.put("sha256", read.getSha256());
        return ok(name, result);
    }

    private String searchCode(UUID workspaceId, String name, JsonNode args) {
        String query = args.path("query").asText("").trim();
        if (query.isBlank()) {
            return error(name, "search_code requires non-empty 'query'");
        }
        ArrayNode matches = objectMapper.createArrayNode();
        for (String path : codeAccess.searchCode(workspaceId, query)) {
            matches.add(path);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("matches", matches);
        return ok(name, result);
    }

    private String writeFile(UUID workspaceId, String name, JsonNode args) {
        String path = args.path("path").asText("").trim();
        String content = args.path("content").asText("");
        if (path.isBlank()) {
            return error(name, "write_file requires non-empty 'path'");
        }
        WorkspaceWriteResult result = writer.writeFile(workspaceId, path, content);
        if (result.isOk()) {
            notifyWrite(result);
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("path", result.getPath());
            return ok(name, resultNode);
        }
        if (result.isInfrastructureFailure()) {
            throw new IllegalStateException("write_file infrastructure failure: "
                    + (result.getError() == null ? "workspace unavailable" : result.getError()));
        }
        return error(name, result.getError() == null ? "write failed" : result.getError());
    }

    private String applyPatch(UUID workspaceId, String name, JsonNode args) {
        String path = args.path("path").asText("").trim();
        String expectedHash = args.path("expectedHash").asText("").trim();
        String patch = args.path("patch").asText("");
        if (path.isBlank()) {
            return error(name, "apply_patch requires non-empty 'path'");
        }
        if (!expectedHash.matches("[0-9a-fA-F]{64}")) {
            return error(name, "apply_patch requires 64-char hex 'expectedHash' from read_file");
        }
        if (patch.isBlank()) {
            return error(name, "apply_patch requires non-empty 'patch'");
        }
        WorkspaceWriteResult result = writer.patchFile(workspaceId, path, expectedHash, patch);
        if (result.isOk()) {
            notifyWrite(result);
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("path", result.getPath());
            return ok(name, resultNode);
        }
        if (result.isInfrastructureFailure()) {
            throw new IllegalStateException("apply_patch infrastructure failure: "
                    + (result.getError() == null ? "workspace unavailable" : result.getError()));
        }
        return error(name, result.getError() == null ? "patch failed" : result.getError());
    }

    private String ok(String tool, ObjectNode result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("tool", tool);
        node.put("ok", true);
        node.set("result", result);
        return node.toString();
    }

    private String error(String tool, String message) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("tool", tool);
        node.put("ok", false);
        node.put("error", message);
        return node.toString();
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
}
