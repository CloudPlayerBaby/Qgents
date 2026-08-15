package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 使用镜像中的 ripgrep 搜索仓库文本。
 */
@Component
@RequiredArgsConstructor
public class FileSearchTool implements SandboxTool {
    private final CommandExecutor executor;

    @Override
    public String name() {
        return "file.search";
    }

    @Override
    public boolean requiresRepository() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) throws InterruptedException {
        String query = ToolArguments.string(arguments, "query", 512);
        String path = ToolArguments.optionalString(arguments, "path", ".", 1024);
        List<String> command = List.of("rg", "--line-number", "--color", "never", "--max-count", "200", "--", query, path);
        CommandExecutionResult result = executor.execute(context.getSandbox(), context.getContainerRepository(), command,
                context.getTimeout());
        int exitCode = result.getExitCode() == 1 ? 0 : result.getExitCode();
        return new ToolResult(exitCode, Map.of("matches", result.getStandardOutput()),
                result.getStandardOutput(), result.getStandardError());
    }
}
