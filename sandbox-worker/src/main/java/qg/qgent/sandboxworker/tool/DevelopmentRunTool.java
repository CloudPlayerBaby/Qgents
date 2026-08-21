package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Coding Agent 的固定开发命令入口。只接受 {@code commandId} 枚举，不提供通用进程、Shell、argv、
 * 环境变量或工作目录参数；所有命令均在已绑定的仓库目录内执行。
 */
@Component
@RequiredArgsConstructor
public class DevelopmentRunTool implements SandboxTool {
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)\\\"?(token|password|secret|api[-_]?key|authorization)\\\"?\\s*[:=]\\s*"
                    + "(?:\\\"[^\\\"]*\\\"|[^\\s,;}]*)");
    private static final Pattern WINDOWS_HOST_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:[A-Z]:[\\\\/])[^\\s,;\\\"]+");
    private static final Pattern UNIX_HOST_PATH = Pattern.compile("(?<![A-Za-z0-9_/:])/(?:home|Users|root|tmp|var|etc|opt|srv)(?:/[^\\s,;\\\"]*)?");
    private static final Pattern REDACTED_BEARER_TAIL = Pattern.compile("(?i)(authorization=\\[redacted])\\s+\\[redacted]");
    private final CommandExecutor executor;

    @Override
    public String name() {
        return "development.run";
    }

    @Override
    public boolean requiresRepository() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) throws InterruptedException {
        if (arguments == null || arguments.size() != 1 || !arguments.containsKey("commandId")) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, "DEVELOPMENT_COMMAND_INVALID",
                    "开发命令只接受 commandId 枚举参数");
        }
        DevelopmentCommandId commandId = commandId(arguments.get("commandId"));
        CommandExecutionResult result = executor.execute(context.getSandbox(), context.getContainerRepository(),
                commandId.argv(), context.getTimeout());
        // 日志会持久化，因此必须在进入 ToolExecutionService 前先脱敏；结果只返回 commandId，绝不保存 argv。
        return new ToolResult(result.getExitCode(), Map.of("commandId", commandId.name()),
                redact(result.getStandardOutput()), redact(result.getStandardError()));
    }

    private DevelopmentCommandId commandId(Object value) {
        if (!(value instanceof String raw) || raw.isBlank()) {
            throw invalid();
        }
        try {
            return DevelopmentCommandId.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            throw invalid();
        }
    }

    private WorkerException invalid() {
        return new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, "DEVELOPMENT_COMMAND_INVALID",
                "不支持的固定开发命令");
    }

    private List<String> redact(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream().map(value -> {
            String sanitized = BEARER.matcher(value == null ? "" : value).replaceAll("Bearer [redacted]");
            sanitized = SENSITIVE.matcher(sanitized).replaceAll("$1=[redacted]");
            sanitized = REDACTED_BEARER_TAIL.matcher(sanitized).replaceAll("$1");
            sanitized = WINDOWS_HOST_PATH.matcher(sanitized).replaceAll("[host path omitted]");
            return UNIX_HOST_PATH.matcher(sanitized).replaceAll("[host path omitted]");
        }).toList();
    }
}
