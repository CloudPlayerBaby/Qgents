package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 受控构造 Git 参数数组的工具处理器。
 * Agent 只能选择已经注册的操作和结构化参数，不能提交任意 Git 命令字符串。
 */
@Component
@RequiredArgsConstructor
public class GitTool implements SandboxTool {
    private static final List<String> NAMES = List.of("git.status", "git.diff", "git.log", "git.add", "git.commit");
    private final CommandExecutor executor;

    @Override
    public String name() {
        return "git.multi";
    }

    /** 返回当前处理器支持的具体 Git 工具名称。 */
    public List<String> names() {
        return NAMES;
    }

    @Override
    public boolean requiresRepository() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        throw new UnsupportedOperationException("Git 工具必须通过具体名称调用");
    }

    /**
     * 根据具体工具名称构造参数数组并在目标仓库中执行。
     */
    public ToolResult execute(String name, ToolContext context, Map<String, Object> arguments)
            throws InterruptedException {
        List<String> command = command(name, arguments);
        CommandExecutionResult result = executor.execute(context.getSandbox(), context.getContainerRepository(), command,
                context.getTimeout());
        return new ToolResult(result.getExitCode(), Map.of("lines", result.getStandardOutput()),
                result.getStandardOutput(), result.getStandardError());
    }

    private List<String> command(String name, Map<String, Object> arguments) {
        return switch (name) {
            case "git.status" -> List.of("git", "status", "--short", "--branch");
            case "git.diff" -> gitDiff(arguments);
            case "git.log" -> List.of("git", "log", "--oneline", "--decorate", "-n",
                    Integer.toString(ToolArguments.integer(arguments, "limit", 20, 1, 100)));
            case "git.add" -> withPaths(List.of("git", "add", "--"), arguments);
            case "git.commit" -> List.of("git", "commit", "-m", ToolArguments.string(arguments, "message", 500));
            default -> throw new IllegalArgumentException("不支持的 Git 工具：" + name);
        };
    }

    private List<String> gitDiff(Map<String, Object> arguments) {
        List<String> command = new ArrayList<>(List.of("git", "diff", "--no-ext-diff", "--no-color"));
        if (Boolean.TRUE.equals(arguments.get("staged"))) {
            command.add("--cached");
        }
        return List.copyOf(command);
    }

    private List<String> withPaths(List<String> prefix, Map<String, Object> arguments) {
        List<String> command = new ArrayList<>(prefix);
        command.addAll(ToolArguments.strings(arguments, "paths", 256, 1024));
        return List.copyOf(command);
    }
}
