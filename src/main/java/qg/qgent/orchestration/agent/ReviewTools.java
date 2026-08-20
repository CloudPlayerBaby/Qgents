package qg.qgent.orchestration.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Review Agent 的白名单只读工具（阶段 B 原生 Tool Calling）：list_files / read_file /
 * search_code。刻意不提供 write_file / apply_patch，从结构上保证 Review Agent 只能读不能写。
 * 每次 run 按 workspaceId 新建实例。
 * <p>
 * 失败语义：参数缺失 / 文件不存在等工具级失败返回 {@code ok=false} 回灌模型自纠；本类不抛
 * 基础设施异常（只读端口不涉及 Workspace 写）。
 */
public class ReviewTools {

    private final UUID workspaceId;
    private final WorkspaceCodeAccess codeAccess;

    public ReviewTools(UUID workspaceId, WorkspaceCodeAccess codeAccess) {
        this.workspaceId = workspaceId;
        this.codeAccess = codeAccess;
    }

    @Tool(name = "list_files", description = "列出工作区所有代码文件的相对路径，无参数")
    public Map<String, Object> listFiles() {
        List<String> files = codeAccess.listFiles(workspaceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("files", files);
        return result;
    }

    @Tool(name = "read_file", description = "读取文件的文本内容与当前 sha256（超过 64KB 会被拒绝）")
    public Map<String, Object> readFile(@ToolParam(description = "工作区内的相对路径") String path) {
        if (path == null || path.isBlank()) {
            return error("read_file requires non-empty 'path'");
        }
        WorkspaceFileReadResult read = codeAccess.readFile(workspaceId, path);
        if (read == null || !read.isOk()) {
            return error(read == null || read.getError() == null
                    ? "file not found or unreadable: " + path : read.getError());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("path", path);
        result.put("content", read.getContent());
        result.put("sha256", read.getSha256());
        if (read.getEndsWithNewline() != null) {
            result.put("endsWithNewline", read.getEndsWithNewline());
        }
        if (read.getNewlineStyle() != null) {
            result.put("newlineStyle", read.getNewlineStyle());
        }
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

    private Map<String, Object> error(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", false);
        result.put("errorCode", classifyError(message));
        result.put("retryable", isRetryable(message));
        result.put("error", message);
        result.put("nextAction", nextAction(message));
        return result;
    }

    private String classifyError(String message) {
        if (message == null) {
            return "TOOL_EXECUTION_FAILED";
        }
        if (message.contains("requires") || message.contains("non-empty")) {
            return "TOOL_ARGUMENT_INVALID";
        }
        if (message.contains("invalid") || message.contains("outside") || message.contains("escapes")) {
            return "TOOL_PATH_INVALID";
        }
        return "TOOL_EXECUTION_FAILED";
    }

    private boolean isRetryable(String message) {
        return message != null && !message.contains("invalid") && !message.contains("outside")
                && !message.contains("escapes");
    }

    private String nextAction(String message) {
        if (message != null && (message.contains("invalid") || message.contains("outside")
                || message.contains("escapes"))) {
            return "改用工作区内的相对路径，不要重复越界调用";
        }
        return "根据 error 修正参数后重试一次，不要原样重复失败调用";
    }
}
