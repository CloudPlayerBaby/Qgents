package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 在授权仓库目录中执行常规开发命令，不经过任意 shell 拼接。 */
@Component
@RequiredArgsConstructor
public class ProcessExecTool implements SandboxTool {
    private static final Set<String> ALLOWED_EXECUTABLES = Set.of(
            "mvn", "gradle", "npm", "pnpm", "npx", "yarn", "node", "python", "python3",
            "make", "gcc", "g++", "javac", "java");
    private final CommandExecutor executor;

    @Override
    public String name() {
        return "process.exec";
    }

    @Override
    public boolean requiresRepository() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) throws InterruptedException {
        List<String> command = ToolArguments.strings(arguments, "command", 64, 4096);
        if (!allowed(command)) {
            throw new qg.qgent.sandboxworker.api.WorkerException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "COMMAND_NOT_ALLOWED", "受控进程只允许常规开发工具和授权 Wrapper");
        }
        CommandExecutionResult result = executor.execute(context.getSandbox(), context.getContainerRepository(), command,
                context.getTimeout());
        return new ToolResult(result.getExitCode(), Map.of("command", command), result.getStandardOutput(),
                result.getStandardError());
    }

    private boolean allowed(List<String> command) {
        if (command.stream().anyMatch(this::unsafeArgument)) {
            return false;
        }
        String executable = command.get(0);
        if (ALLOWED_EXECUTABLES.contains(executable)) {
            return true;
        }
        if (!"sh".equals(executable) || command.size() < 2) {
            return false;
        }
        String wrapper = command.get(1);
        return "./gradlew".equals(wrapper) || "./mvnw".equals(wrapper);
    }

    private boolean unsafeArgument(String value) {
        if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return true;
        }
        if (value.indexOf(';') >= 0 || value.indexOf('|') >= 0 || value.indexOf('&') >= 0
                || value.indexOf('`') >= 0 || value.indexOf('$') >= 0 || value.indexOf('<') >= 0
                || value.indexOf('>') >= 0) {
            return true;
        }
        String normalized = value.toLowerCase(Locale.ROOT).replace('\\', '/');
        return normalized.startsWith("/") || normalized.matches("^[a-z]:/.*")
                || normalized.equals("..") || normalized.startsWith("../") || normalized.contains("/../");
    }
}
