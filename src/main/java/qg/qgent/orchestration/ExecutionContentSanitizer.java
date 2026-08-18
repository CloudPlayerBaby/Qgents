package qg.qgent.orchestration;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 执行反馈进入模型、用户产物或领域结果前的统一内容脱敏器。
 */
public final class ExecutionContentSanitizer {

    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)\\b(token|password|secret|api[-_]?key|authorization)\\b\\s*[:=]\\s*([^\\s,;}]*)");
    private static final Pattern WINDOWS_HOST_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:[A-Za-z]:[\\\\/])[^\\s,;\"']+");
    private static final Pattern UNIX_HOST_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_])/(?:home|Users|root|tmp|var|etc|opt|srv)(?:/[^\\s,;\"']*)?");

    private ExecutionContentSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String sanitized = BEARER.matcher(value).replaceAll("Bearer [redacted]");
        sanitized = SENSITIVE_VALUE.matcher(sanitized).replaceAll("$1=[redacted]");
        sanitized = WINDOWS_HOST_PATH.matcher(sanitized).replaceAll("[host path omitted]");
        return UNIX_HOST_PATH.matcher(sanitized).replaceAll("[host path omitted]");
    }

    public static String stableInfrastructureCode(String code) {
        if (code == null) {
            return "FAILED_INFRASTRUCTURE";
        }
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "LLM_FINISH_LENGTH", "LLM_CONTEXT_LIMIT", "LLM_TOOL_NOT_ALLOWED",
                    "LLM_TOOL_ARGUMENT_INVALID", "LLM_TOOL_CALL_MALFORMED", "AGENT_RUN_TIMEOUT",
                    "SANDBOX_WORKER_UNAVAILABLE", "SANDBOX_WORKER_ERROR", "GIT_BASE_REF_NOT_FOUND",
                    "GIT_REF_NOT_FOUND",
                    "GIT_STORE_FETCH_FAILED", "GIT_STORE_SYNC_INVALID", "GIT_REMOTE_SHA_MISMATCH",
                    "GITHUB_API_UNAVAILABLE", "WORKER_PUSH_FAILED" ->
                    code.toUpperCase(Locale.ROOT);
            default -> "FAILED_INFRASTRUCTURE";
        };
    }

    public static String infrastructureDescription(String code) {
        return switch (stableInfrastructureCode(code)) {
            case "LLM_FINISH_LENGTH" -> "模型结构化输出因长度上限未完成";
            case "LLM_CONTEXT_LIMIT" -> "模型在工具轮次上限内未能收敛";
            case "LLM_TOOL_NOT_ALLOWED", "LLM_TOOL_ARGUMENT_INVALID", "LLM_TOOL_CALL_MALFORMED" ->
                    "模型工具协议未能稳定完成";
            case "AGENT_RUN_TIMEOUT" -> "Agent 执行超过相位时限";
            case "SANDBOX_WORKER_UNAVAILABLE" -> "Sandbox Worker 当前不可用";
            case "GIT_BASE_REF_NOT_FOUND" -> "找不到任务指定的基线分支或提交";
            case "GIT_REF_NOT_FOUND" -> "Worker Git Store 中找不到指定分支或提交";
            case "GIT_STORE_FETCH_FAILED", "GIT_STORE_SYNC_INVALID", "GIT_REMOTE_SHA_MISMATCH" ->
                    "代码仓库同步失败";
            case "GITHUB_API_UNAVAILABLE" -> "GitHub 服务当前不可用";
            case "WORKER_PUSH_FAILED" -> "代码推送到仓库失败";
            default -> "执行基础设施暂不可用";
        };
    }
}
