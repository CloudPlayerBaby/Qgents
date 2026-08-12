package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;

import java.util.List;
import java.util.Map;

/** 在仓库目录中执行不经过 shell 拼接的参数数组命令。 */
@Component
@RequiredArgsConstructor
public class ProcessExecTool implements SandboxTool {
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
        CommandExecutionResult result = executor.execute(context.getSandbox(), context.getContainerRepository(), command,
                context.getTimeout());
        return new ToolResult(result.getExitCode(), Map.of("command", command), result.getStandardOutput(),
                result.getStandardError());
    }
}
