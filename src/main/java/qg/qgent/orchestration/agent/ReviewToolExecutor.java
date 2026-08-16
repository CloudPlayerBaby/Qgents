package qg.qgent.orchestration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;

import java.util.UUID;

/**
 * Review Agent 的白名单只读工具执行器：list_files / read_file / search_code。
 * <p>
 * 刻意不提供 write_file / apply_patch：未知工具名（含两者）一律返回 unknown tool 错误，
 * 从结构上保证 Review Agent 无法修改 Workspace。结果格式化为可回灌给 LLM 的 JSON。
 */
public class ReviewToolExecutor {

    private final WorkspaceCodeAccess codeAccess;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewToolExecutor(WorkspaceCodeAccess codeAccess) {
        this.codeAccess = codeAccess;
    }

    /**
     * 执行一次只读工具调用并返回 JSON 结果字符串。
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
