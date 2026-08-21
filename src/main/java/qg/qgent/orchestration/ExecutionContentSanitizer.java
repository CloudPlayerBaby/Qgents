package qg.qgent.orchestration;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 执行反馈进入模型、用户产物或领域结果前的统一内容脱敏器。
 */
public final class ExecutionContentSanitizer {

    private static final String GENERIC_FAILURE_DESCRIPTION = "任务执行失败，请查看执行记录";

    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern SENSITIVE_VALUE = Pattern.compile(
            "(?i)\\b(token|password|secret|api[-_]?key|authorization)\\b\\s*[:=]\\s*([^\\s,;}]*)");
    private static final Pattern WINDOWS_HOST_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:[A-Za-z]:[\\\\/])[^\\s,;\"']+");
    private static final Pattern UNIX_HOST_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_])/(?:home|Users|root|tmp|var|etc|opt|srv)(?:/[^\\s,;\"']*)?");
    private static final Pattern ENVIRONMENT_ASSIGNMENT = Pattern.compile(
            "\\b[A-Z][A-Z0-9_]{2,}\\s*=\\s*[^\\s,;}\\\"]+");
    private static final Pattern URL = Pattern.compile("(?i)https?://[^\\s,;\"']+");
    private static final Pattern COMMAND_VALUE = Pattern.compile(
            "(?i)\\b(command|cmd|argv|command line)\\b\\s*[:=]\\s*[^\\r\\n]+");
    private static final Pattern RAW_OUTPUT = Pattern.compile(
            "(?is)\\b(stdout|stderr|stack[ -]?trace)\\b\\s*[:=]\\s*.*");
    private static final Pattern STACK_FRAME_LINE = Pattern.compile("(?m)^\\s*at\\s+[^\\r\\n]+$");
    private static final Pattern PROCESS_COMMAND = Pattern.compile(
            "(?i)\\b(?:process|tool)\\s+(?:failed|error|exited|execution failed).*?"
                    + "(?:running|with command)\\s+[^\\r\\n]+");

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

    /**
     * 供仅限后端诊断记录使用的错误详情脱敏。除通用凭据和宿主路径外，也去除环境变量赋值及
     * 外部地址，避免故障信息成为凭据、部署拓扑或 Worker 端点的旁路泄露渠道。
     */
    public static String sanitizeDiagnosticDetail(String value) {
        String sanitized = sanitize(value);
        sanitized = ENVIRONMENT_ASSIGNMENT.matcher(sanitized).replaceAll("[environment omitted]");
        sanitized = URL.matcher(sanitized).replaceAll("[endpoint omitted]");
        sanitized = COMMAND_VALUE.matcher(sanitized).replaceAll("[command omitted]");
        sanitized = PROCESS_COMMAND.matcher(sanitized).replaceAll("[command omitted]");
        sanitized = STACK_FRAME_LINE.matcher(sanitized).replaceAll("[stack frame omitted]");
        return RAW_OUTPUT.matcher(sanitized).replaceAll("[raw output omitted]");
    }

    public static String stableInfrastructureCode(String code) {
        if (code == null) {
            return "FAILED_INFRASTRUCTURE";
        }
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "LLM_FINISH_LENGTH", "LLM_CONTEXT_LIMIT", "LLM_TOOL_NOT_ALLOWED",
                    "LLM_TOOL_ARGUMENT_INVALID", "LLM_TOOL_CALL_MALFORMED", "AGENT_RUN_TIMEOUT",
                    "SANDBOX_WORKER_UNAVAILABLE", "SANDBOX_WORKER_ERROR", "GIT_BASE_REF_NOT_FOUND",
                    "GIT_BRANCH_NOT_FOUND", "GIT_REF_NOT_FOUND",
                    "GIT_STORE_FETCH_FAILED", "GIT_STORE_SYNC_INVALID", "GIT_REMOTE_SHA_MISMATCH",
                    "GITHUB_API_UNAVAILABLE", "WORKER_PUSH_FAILED", "WORKSPACE_WRITE_LEASE_LOST",
                    "SANDBOX_NOT_FOUND", "DOCKER_EXEC_FAILED", "TEST_EXECUTION_TIMEOUT",
                    "BUILD_ENVIRONMENT_UNAVAILABLE", "TEST_DEPENDENCY_UNAVAILABLE",
                    "TEST_NETWORK_UNAVAILABLE", "TEST_SERVICE_UNAVAILABLE" ->
                    code.toUpperCase(Locale.ROOT);
            case "GIT_REMOTE_AUTH_FAILED", "GIT_REMOTE_BRANCH_NOT_FOUND", "GIT_REMOTE_REPOSITORY_UNAVAILABLE",
                    "GIT_REMOTE_RATE_LIMITED", "GIT_REMOTE_NETWORK_FAILED" -> "GIT_STORE_FETCH_FAILED";
            case "DRY_RUN_TIMEOUT" -> "DRY_RUN_TIMEOUT";
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
            case "SANDBOX_WORKER_UNAVAILABLE", "SANDBOX_WORKER_ERROR" -> "Sandbox Worker 当前不可用";
            case "WORKSPACE_WRITE_LEASE_LOST" -> "Workspace 写入租约已失效";
            case "SANDBOX_NOT_FOUND" -> "测试 Sandbox 已不存在或已过期";
            case "DOCKER_EXEC_FAILED" -> "Sandbox 内进程启动失败";
            case "TEST_EXECUTION_TIMEOUT" -> "测试命令执行超时";
            case "BUILD_ENVIRONMENT_UNAVAILABLE" -> "测试构建环境不可用";
            case "TEST_DEPENDENCY_UNAVAILABLE" -> "测试依赖解析或构建环境不可用";
            case "TEST_NETWORK_UNAVAILABLE" -> "测试执行网络不可达";
            case "TEST_SERVICE_UNAVAILABLE" -> "测试所需服务（数据库/缓存/消息队列）连接失败";
            case "GIT_BASE_REF_NOT_FOUND" -> "找不到任务指定的基线分支或提交";
            case "GIT_BRANCH_NOT_FOUND" -> "仓库不存在指定的基线分支，请在项目仓库配置中选择真实存在的分支";
            case "GIT_REF_NOT_FOUND" -> "Worker Git Store 中找不到指定分支或提交";
            case "GIT_STORE_FETCH_FAILED", "GIT_STORE_SYNC_INVALID", "GIT_REMOTE_SHA_MISMATCH" ->
                    "代码仓库同步失败";
            case "GITHUB_API_UNAVAILABLE" -> "GitHub 服务当前不可用";
            case "DRY_RUN_TIMEOUT" -> "Dry Run 执行超时";
            case "WORKER_PUSH_FAILED" -> "代码推送到仓库失败";
            default -> "执行基础设施暂不可用";
        };
    }

    /**
     * 将内部失败码映射为稳定的用户可见说明；不返回模型原文或异常消息。
     */
    public static String userFailureDescription(String code) {
        if (code == null || code.isBlank()) {
            return GENERIC_FAILURE_DESCRIPTION;
        }
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "EXECUTION_FAILED" -> "执行步骤未通过，请查看脱敏失败摘要";
            case "LLM_TOOL_CALL_MALFORMED", "LLM_TOOL_NOT_ALLOWED", "LLM_TOOL_ARGUMENT_INVALID" ->
                    "模型工具协议未能稳定完成";
            case "CODING_NO_ACTUAL_CHANGE" -> "代码步骤未产生实际文件变更";
            case "FILE_PATCH_FAILED" -> "补丁无法应用，请重新读取文件后重试";
            case "FILE_HASH_MISMATCH" -> "文件内容已变化，请重新读取后重试";
            case "TOOL_PATH_INVALID" -> "工具路径或目标文件无效";
            case "TOOL_ARGUMENT_INVALID" -> "工具参数无效";
            case "PROCESS_EXIT_NONZERO" -> "工具进程执行失败";
            case "AGENT_RUN_TIMEOUT" -> "Agent 执行超时";
            case "UNCLASSIFIED_FAILURE" -> "Agent 执行未完成且未能归类具体原因，请查看执行记录";
            case "TEST_COMMAND_NOT_FOUND" -> "未检测到受支持的项目/测试命令，未执行测试";
            case "REVIEW_ASSERTION_TARGET_NOT_FOUND" -> "审查未找到任务要求的验收目标（文件/函数/接口/选择器等），请补齐后重新审查";
            case "TASK_QUALITY_LOOPS_EXHAUSTED" -> "任务多次未通过质量验证，修复循环已耗尽";
            case "QUALITY_REPAIR_STEP_UNAVAILABLE" -> "任务没有可写的开发步骤，无法继续修复";
            case "TASK_FINALIZATION_DIFF" -> "最终 Diff 生成失败";
            case "FAILED_INFRASTRUCTURE", "LLM_FINISH_LENGTH", "LLM_CONTEXT_LIMIT", "SANDBOX_WORKER_UNAVAILABLE",
                    "SANDBOX_WORKER_ERROR", "WORKSPACE_WRITE_LEASE_LOST", "SANDBOX_NOT_FOUND",
                    "DOCKER_EXEC_FAILED", "TEST_EXECUTION_TIMEOUT", "BUILD_ENVIRONMENT_UNAVAILABLE",
                    "TEST_DEPENDENCY_UNAVAILABLE", "TEST_NETWORK_UNAVAILABLE", "TEST_SERVICE_UNAVAILABLE",
                    "GIT_BASE_REF_NOT_FOUND", "GIT_BRANCH_NOT_FOUND", "GIT_REF_NOT_FOUND",
                    "GIT_STORE_FETCH_FAILED",
                    "GIT_STORE_SYNC_INVALID", "GIT_REMOTE_SHA_MISMATCH", "GITHUB_API_UNAVAILABLE",
                    "GIT_REMOTE_AUTH_FAILED", "GIT_REMOTE_BRANCH_NOT_FOUND", "GIT_REMOTE_REPOSITORY_UNAVAILABLE",
                    "GIT_REMOTE_RATE_LIMITED", "GIT_REMOTE_NETWORK_FAILED",
                    "WORKER_PUSH_FAILED", "DRY_RUN_TIMEOUT" -> infrastructureDescription(code);
            default -> GENERIC_FAILURE_DESCRIPTION;
        };
    }

    /**
     * 过滤未知内部码，避免将未定义的实现细节作为公开接口字段返回。
     */
    public static String publicFailureCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "EXECUTION_FAILED", "FAILED_INFRASTRUCTURE", "LLM_TOOL_CALL_MALFORMED", "LLM_TOOL_NOT_ALLOWED",
                    "LLM_TOOL_ARGUMENT_INVALID", "CODING_NO_ACTUAL_CHANGE", "FILE_PATCH_FAILED",
                    "FILE_HASH_MISMATCH", "TOOL_PATH_INVALID", "TOOL_ARGUMENT_INVALID",
                    "PROCESS_EXIT_NONZERO", "AGENT_RUN_TIMEOUT", "TOOL_EXECUTION_FAILED",
                    "UNCLASSIFIED_FAILURE",
                    "LLM_FINISH_LENGTH", "LLM_CONTEXT_LIMIT", "SANDBOX_WORKER_UNAVAILABLE",
                    "SANDBOX_WORKER_ERROR", "WORKSPACE_WRITE_LEASE_LOST", "SANDBOX_NOT_FOUND",
                    "DOCKER_EXEC_FAILED", "TEST_EXECUTION_TIMEOUT", "BUILD_ENVIRONMENT_UNAVAILABLE",
                    "TEST_DEPENDENCY_UNAVAILABLE", "TEST_NETWORK_UNAVAILABLE", "TEST_SERVICE_UNAVAILABLE",
                    "TEST_COMMAND_NOT_FOUND", "REVIEW_ASSERTION_TARGET_NOT_FOUND",
                    "TASK_QUALITY_LOOPS_EXHAUSTED", "QUALITY_REPAIR_STEP_UNAVAILABLE", "TASK_FINALIZATION_DIFF",
                    "GIT_BASE_REF_NOT_FOUND", "GIT_BRANCH_NOT_FOUND", "GIT_REF_NOT_FOUND",
                    "GIT_STORE_FETCH_FAILED",
                    "GIT_STORE_SYNC_INVALID", "GIT_REMOTE_SHA_MISMATCH", "GITHUB_API_UNAVAILABLE",
                    "WORKER_PUSH_FAILED", "DRY_RUN_TIMEOUT" -> code.toUpperCase(Locale.ROOT);
            case "GIT_REMOTE_AUTH_FAILED", "GIT_REMOTE_BRANCH_NOT_FOUND", "GIT_REMOTE_REPOSITORY_UNAVAILABLE",
                    "GIT_REMOTE_RATE_LIMITED", "GIT_REMOTE_NETWORK_FAILED" -> "GIT_STORE_FETCH_FAILED";
            default -> null;
        };
    }

    /**
     * 仅依据稳定失败码计算用户可重试提示；未知码不默认暴露为可重试。
     */
    public static boolean userFailureRetryable(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        return switch (code.toUpperCase(Locale.ROOT)) {
            case "EXECUTION_FAILED", "FAILED_INFRASTRUCTURE", "LLM_TOOL_CALL_MALFORMED", "LLM_TOOL_NOT_ALLOWED",
                    "LLM_TOOL_ARGUMENT_INVALID", "CODING_NO_ACTUAL_CHANGE", "FILE_PATCH_FAILED",
                    "FILE_HASH_MISMATCH", "TOOL_PATH_INVALID", "TOOL_ARGUMENT_INVALID",
                    "PROCESS_EXIT_NONZERO", "AGENT_RUN_TIMEOUT", "TOOL_EXECUTION_FAILED",
                    "LLM_FINISH_LENGTH", "LLM_CONTEXT_LIMIT", "SANDBOX_WORKER_UNAVAILABLE",
                    "SANDBOX_WORKER_ERROR", "WORKSPACE_WRITE_LEASE_LOST", "SANDBOX_NOT_FOUND",
                    "DOCKER_EXEC_FAILED", "TEST_EXECUTION_TIMEOUT", "BUILD_ENVIRONMENT_UNAVAILABLE",
                    "TEST_DEPENDENCY_UNAVAILABLE", "TEST_NETWORK_UNAVAILABLE", "TEST_SERVICE_UNAVAILABLE",
                    "GIT_BASE_REF_NOT_FOUND", "GIT_BRANCH_NOT_FOUND", "GIT_REF_NOT_FOUND",
                    "GIT_STORE_FETCH_FAILED",
                    "GIT_STORE_SYNC_INVALID", "GIT_REMOTE_SHA_MISMATCH", "GITHUB_API_UNAVAILABLE",
                    "REVIEW_ASSERTION_TARGET_NOT_FOUND", "TASK_QUALITY_LOOPS_EXHAUSTED",
                    "QUALITY_REPAIR_STEP_UNAVAILABLE", "TASK_FINALIZATION_DIFF",
                    "WORKER_PUSH_FAILED", "DRY_RUN_TIMEOUT" -> true;
            default -> false;
        };
    }
}
