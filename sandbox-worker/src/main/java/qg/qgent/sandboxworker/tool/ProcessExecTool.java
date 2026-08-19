package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 在仓库目录中执行受控测试命令，不经过任意 shell 拼接。 */
@Component
@RequiredArgsConstructor
public class ProcessExecTool implements SandboxTool {
    private static final Set<List<String>> ALLOWED_TEST_COMMANDS = Set.of(
            List.of("mvn", "test"),
            List.of("gradle", "test"),
            List.of("npm", "test"),
            List.of("sh", "./mvnw", "test"),
            List.of("sh", "./gradlew", "test"));
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
        if (!ALLOWED_TEST_COMMANDS.contains(command)) {
            throw new qg.qgent.sandboxworker.api.WorkerException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "COMMAND_NOT_ALLOWED", "受控进程只允许固定的测试命令向量");
        }
        CommandExecutionResult result = executor.execute(context.getSandbox(), context.getContainerRepository(), command,
                context.getTimeout());
        return new ToolResult(result.getExitCode(), Map.of("command", command), result.getStandardOutput(),
                result.getStandardError());
    }
}
