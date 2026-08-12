package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;

import java.util.UUID;

/**
 * 执行 Coding Agent 的白名单工具调用，并把结果格式化为可回灌给 LLM 的 JSON 字符串。
 * <p>
 * 只允许 list_files / read_file / search_code（只读，经 {@link WorkspaceCodeAccess}）
 * 与 write_file（写，经 {@link WorkspaceCodeWriter}）。参数缺失、越界、文件不存在或
 * 写入失败时返回 ok=false 的结构化错误而不是抛出异常，让模型能基于错误信息自行纠正；
 * 只有工具名缺失或未知这类协议错误才返回明确错误结果。工具结果字符串不携带 Secret。
 */
public class CodingToolExecutor {

    private final WorkspaceCodeAccess codeAccess;
    private final WorkspaceCodeWriter writer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CodingToolExecutor(WorkspaceCodeAccess codeAccess, WorkspaceCodeWriter writer) {
        this.codeAccess = codeAccess;
        this.writer = writer;
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
        String content = codeAccess.readFile(workspaceId, path);
        if (content == null) {
            return error(name, "file not found or unreadable: " + path);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.put("path", path);
        result.put("content", content);
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
            ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("path", result.getPath());
            return ok(name, resultNode);
        }
        return error(name, result.getError() == null ? "write failed" : result.getError());
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
}
