package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;
import qg.qgent.orchestration.tool.WorkspaceDirectoryResult;
import qg.qgent.orchestration.tool.WorkspaceChangeResult;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final TaskStepPathPolicy pathPolicy;
    /** 最近一次工具级失败，供最终无变更门禁保留可操作根因。 */
    private String lastToolError;
    private final Map<String, Integer> patchFailuresByPath = new HashMap<>();
    private static final int PATCH_FAILURE_ESCALATION_THRESHOLD = 3;

    public CodingToolExecutor(WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer) {
        this(codeAccess, writer, List.of());
    }

    public CodingToolExecutor(WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer,
                              Collection<String> allowedPaths) {
        this.codeAccess = codeAccess;
        this.writer = writer;
        this.pathPolicy = TaskStepPathPolicy.of(allowedPaths);
    }

    public CodingToolExecutor(WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer,
                              Collection<String> allowedPaths, Map<String, Integer> previousPatchFailures) {
        this(codeAccess, writer, allowedPaths);
        if (previousPatchFailures != null) {
            previousPatchFailures.forEach((path, count) -> {
                if (path != null && !path.isBlank() && count != null && count > 0) {
                    patchFailuresByPath.put(path, Math.min(count, PATCH_FAILURE_ESCALATION_THRESHOLD));
                }
            });
        }
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
            case "create_directory" -> createDirectory(workspaceId, name, args);
            case "write_file" -> writeFile(workspaceId, name, args);
            case "apply_patch" -> applyPatch(workspaceId, name, args);
            case "replace_file" -> replaceFile(workspaceId, name, args);
            default -> error(name, "unknown tool '" + name + "'");
        };
    }

    public String getLastToolError() {
        return lastToolError;
    }

    public Map<String, Integer> getPatchFailureCounts() {
        return Collections.unmodifiableMap(new HashMap<>(patchFailuresByPath));
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
        String denied = ensureWritablePath(path);
        if (denied != null) {
            return error(name, denied);
        }
        if (codeAccess.listFiles(workspaceId).stream().map(p -> p.replace('\\', '/'))
                .anyMatch(p -> p.equals(path.replace('\\', '/')))) {
            return error(name, "write_file only creates new files; '" + path
                    + "' already exists, use apply_patch or replace_file");
        }
        WorkspaceWriteResult result = writer.writeFile(workspaceId, path, content);
        if (result.isOk()) {
            lastToolError = null;
            if (result.isChanged()) {
                notifyChange(result);
            }
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("path", result.getPath());
            resultNode.put("changed", result.isChanged());
            return ok(name, resultNode);
        }
        if (result.isInfrastructureFailure()) {
            throw new IllegalStateException("write_file infrastructure failure: "
                    + (result.getError() == null ? "workspace unavailable" : result.getError()));
        }
        return error(name, result.getFailureCode(), result.getError() == null ? "write failed" : result.getError());
    }

    private String createDirectory(UUID workspaceId, String name, JsonNode args) {
        String path = args.path("path").asText("").trim();
        if (path.isBlank()) {
            return error(name, "create_directory requires non-empty 'path'");
        }
        String denied = ensureDirectoryPath(path);
        if (denied != null) {
            return error(name, denied);
        }
        WorkspaceDirectoryResult result = writer.createDirectory(workspaceId, path);
        if (result.isOk()) {
            if (result.isChanged()) {
                notifyChange(result);
            }
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("path", result.getPath());
            resultNode.put("created", result.isCreated());
            return ok(name, resultNode);
        }
        if (result.isInfrastructureFailure()) {
            throw new IllegalStateException("create_directory infrastructure failure: "
                    + (result.getError() == null ? "workspace unavailable" : result.getError()));
        }
        return error(name, result.getFailureCode(), result.getError() == null ? "directory creation failed" : result.getError());
    }

    private String applyPatch(UUID workspaceId, String name, JsonNode args) {
        String path = args.path("path").asText("").trim();
        String expectedHash = args.path("expectedHash").asText("").trim();
        String patch = args.path("patch").asText("");
        if (path.isBlank()) {
            return error(name, "apply_patch requires non-empty 'path'");
        }
        String denied = ensureWritablePath(path);
        if (denied != null) {
            return error(name, denied);
        }
        if (!expectedHash.matches("[0-9a-fA-F]{64}")) {
            return error(name, "apply_patch requires 64-char hex 'expectedHash' from read_file");
        }
        if (patch.isBlank()) {
            return error(name, "apply_patch requires non-empty 'patch'");
        }
        WorkspaceWriteResult result = writer.patchFile(workspaceId, path, expectedHash, patch);
        if (result.isOk()) {
            patchFailuresByPath.remove(path);
            if (result.isChanged()) {
                notifyChange(result);
            }
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("path", result.getPath());
            resultNode.put("changed", result.isChanged());
            return ok(name, resultNode);
        }
        if (result.isInfrastructureFailure()) {
            throw new IllegalStateException("apply_patch infrastructure failure: "
                    + (result.getError() == null ? "workspace unavailable" : result.getError()));
        }
        return patchFailed(name, path, result.getFailureCode(), result.getError() == null ? "patch failed" : result.getError());
    }

    private String replaceFile(UUID workspaceId, String name, JsonNode args) {
        String path = args.path("path").asText("").trim();
        String expectedHash = args.path("expectedHash").asText("").trim();
        String content = args.path("content").asText("");
        if (path.isBlank()) {
            return error(name, "replace_file requires non-empty 'path'");
        }
        String denied = ensureWritablePath(path);
        if (denied != null) {
            return error(name, denied);
        }
        if (!expectedHash.matches("[0-9a-fA-F]{64}")) {
            return error(name, "replace_file requires 64-char hex 'expectedHash' from read_file");
        }
        WorkspaceWriteResult result = writer.replaceFile(workspaceId, path, expectedHash, content);
        if (result.isOk()) {
            patchFailuresByPath.remove(path);
            lastToolError = null;
            if (result.isChanged()) {
                notifyChange(result);
            }
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("path", result.getPath());
            resultNode.put("changed", result.isChanged());
            resultNode.put("oldSha", expectedHash);
            resultNode.put("newSha", result.getNewSha256());
            return ok(name, resultNode);
        }
        if (result.isInfrastructureFailure()) {
            throw new IllegalStateException("replace_file infrastructure failure: "
                    + (result.getError() == null ? "workspace unavailable" : result.getError()));
        }
        String message = result.getError() == null ? "replace failed" : result.getError();
        String failure = error(name, result.getFailureCode(), message);
        if (patchFailuresByPath.getOrDefault(path, 0) >= PATCH_FAILURE_ESCALATION_THRESHOLD) {
            lastToolError = "TOOL_PATCH_REPAIR_REQUIRED: replace_file failed: " + message;
        }
        return failure;
    }

    private String patchFailed(String tool, String path, String failureCode, String message) {
        int failures = patchFailuresByPath.merge(path, 1, Integer::sum);
        if (failures >= PATCH_FAILURE_ESCALATION_THRESHOLD) {
            lastToolError = "TOOL_PATCH_REPAIR_REQUIRED: " + message;
            ObjectNode node = objectMapper.createObjectNode();
            node.put("tool", tool);
            node.put("ok", false);
            node.put("errorCode", "TOOL_PATCH_REPAIR_REQUIRED");
            node.put("retryable", true);
            node.put("error", message);
            node.put("nextAction", "该文件已连续 " + PATCH_FAILURE_ESCALATION_THRESHOLD
                    + " 次补丁失败；请先 read_file 获取最新内容和 sha256，再调用 replace_file 提供完整文件内容");
            return node.toString();
        }
        return error(tool, failureCode, message);
    }

    private String ok(String tool, ObjectNode result) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("tool", tool);
        node.put("ok", true);
        node.set("result", result);
        return node.toString();
    }

    private String error(String tool, String message) {
        return error(tool, null, message);
    }

    private String error(String tool, String failureCode, String message) {
        lastToolError = message;
        ObjectNode node = objectMapper.createObjectNode();
        node.put("tool", tool);
        node.put("ok", false);
        node.put("errorCode", classifyError(failureCode, message));
        node.put("retryable", isRetryable(failureCode, message));
        node.put("error", message);
        node.put("nextAction", nextAction(failureCode, message));
        return node.toString();
    }

    private String classifyError(String message) {
        return classifyError(null, message);
    }

    private String classifyError(String failureCode, String message) {
        if (failureCode != null) {
            return switch (failureCode) {
                case "FILE_PATCH_FAILED" -> "TOOL_PATCH_FORMAT_INVALID";
                case "TOOL_PATCH_REPAIR_REQUIRED" -> "TOOL_PATCH_REPAIR_REQUIRED";
                case "FILE_HASH_MISMATCH" -> "TOOL_CONFLICT";
                case "TOOL_PATH_INVALID" -> "TOOL_PATH_INVALID";
                case "TOOL_ARGUMENT_INVALID", "COMMAND_NOT_ALLOWED" -> "TOOL_ARGUMENT_INVALID";
                default -> "TOOL_EXECUTION_FAILED";
            };
        }
        if (message == null) {
            return "TOOL_EXECUTION_FAILED";
        }
        if (message.contains("FILE_PATCH_FAILED") || message.contains("PATCH_")
                || message.contains("补丁") || message.contains("hunk")) {
            return "TOOL_PATCH_FORMAT_INVALID";
        }
        if (message.contains("FILE_HASH_MISMATCH") || message.contains("hash")
                || message.contains("changed since read")) {
            return "TOOL_CONFLICT";
        }
        if (message.contains("requires") || message.contains("non-empty")) {
            return "TOOL_ARGUMENT_INVALID";
        }
        if (message.contains("outside") || message.contains("escapes") || message.contains("invalid")) {
            return "TOOL_PATH_INVALID";
        }
        return "TOOL_EXECUTION_FAILED";
    }

    private boolean isRetryable(String message) {
        return isRetryable(null, message);
    }

    private boolean isRetryable(String failureCode, String message) {
        if (failureCode != null) {
            return "FILE_PATCH_FAILED".equals(failureCode) || "FILE_HASH_MISMATCH".equals(failureCode)
                    || "TOOL_PATCH_REPAIR_REQUIRED".equals(failureCode);
        }
        if (message != null && (message.contains("FILE_PATCH_FAILED") || message.contains("PATCH_")
                || message.contains("补丁") || message.contains("hunk"))) {
            return true;
        }
        return message != null && !message.contains("outside") && !message.contains("escapes")
                && !message.contains("invalid") && !message.contains("already exists");
    }

    private String nextAction(String message) {
        return nextAction(null, message);
    }

    private String nextAction(String failureCode, String message) {
        if ("FILE_PATCH_FAILED".equals(failureCode)) {
            return "不要重复原 patch；先 read_file 获取最新内容和 sha256，再按实际内容重新生成完整 unified diff；新文件改用 write_file";
        }
        if ("TOOL_PATCH_REPAIR_REQUIRED".equals(failureCode)) {
            return "该文件已连续补丁失败；请先 read_file 获取最新内容，再调用 replace_file 提供完整文件内容";
        }
        if ("FILE_HASH_MISMATCH".equals(failureCode)) {
            return "先重新 read_file 获取当前 sha256，再用 apply_patch";
        }
        if (message != null && (message.contains("FILE_PATCH_FAILED") || message.contains("PATCH_")
                || message.contains("补丁") || message.contains("hunk"))) {
            return "不要重复原 patch；先 read_file 获取最新内容和 sha256，再按实际内容重新生成完整 unified diff；新文件改用 write_file";
        }
        if (message != null && (message.contains("FILE_HASH_MISMATCH") || message.contains("hash")
                || message.contains("changed since read"))) {
            return "先重新 read_file 获取当前 sha256，再用 apply_patch";
        }
        return "根据 error 修正参数后重试一次，不要原样重复失败调用";
    }

    private String ensureWritablePath(String path) {
        if (TaskStepPathPolicy.normalize(path) == null) {
            return "path is invalid or escapes the workspace";
        }
        if (!pathPolicy.allows(path)) {
            return "path is outside the current TaskStep allowed paths";
        }
        return null;
    }

    private String ensureDirectoryPath(String path) {
        if (TaskStepPathPolicy.normalize(path) == null) {
            return "path is invalid or escapes the workspace";
        }
        if (!pathPolicy.allowsDirectory(path)) {
            return "path is outside the current TaskStep allowed paths";
        }
        return null;
    }

    /**
     * 成功写后通知预览回调；回调失败只记日志，绝不破坏 Coding 主循环。
     */
    private void notifyChange(WorkspaceChangeResult result) {
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
