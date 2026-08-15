package qg.qgent.sandboxworker.tool;

import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * 维护 Agent Sandbox 允许调用的非 Git 工具白名单。
 */
@Component
public class ToolRegistry {
    private final Map<String, SandboxTool> tools;

    public ToolRegistry(List<SandboxTool> handlers) {
        tools = handlers.stream().collect(Collectors.toUnmodifiableMap(SandboxTool::name, Function.identity()));
    }

    public ToolResult execute(String name, ToolContext context, Map<String, Object> arguments) throws InterruptedException {
        return require(name).execute(context, arguments);
    }

    public boolean requiresRepository(String name) {
        return require(name).requiresRepository();
    }

    private SandboxTool require(String name) {
        SandboxTool tool = tools.get(name);
        if (tool == null)
            throw new WorkerException(UNPROCESSABLE_ENTITY, "TOOL_NOT_SUPPORTED", "Worker 不支持该工具：" + name);
        return tool;
    }
}
