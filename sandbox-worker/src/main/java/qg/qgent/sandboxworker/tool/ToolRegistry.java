package qg.qgent.sandboxworker.tool;

import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/** 维护 Worker 允许 Agent 调用的工具白名单。 */
@Component
public class ToolRegistry {
    private final Map<String, SandboxTool> tools;
    private final GitTool gitTool;

    public ToolRegistry(List<SandboxTool> handlers, GitTool gitTool) {
        Map<String, SandboxTool> registered = new HashMap<>();
        handlers.stream().filter(handler -> !(handler instanceof GitTool))
                .forEach(handler -> registered.put(handler.name(), handler));
        this.tools = Map.copyOf(registered);
        this.gitTool = gitTool;
    }

    public ToolResult execute(String name, ToolContext context, Map<String, Object> arguments)
            throws InterruptedException {
        if (gitTool.names().contains(name)) {
            return gitTool.execute(name, context, arguments);
        }
        SandboxTool tool = tools.get(name);
        if (tool == null) {
            throw new WorkerException(UNPROCESSABLE_ENTITY, "TOOL_NOT_SUPPORTED", "Worker 不支持该工具：" + name);
        }
        return tool.execute(context, arguments);
    }

    public boolean requiresRepository(String name) {
        if (gitTool.names().contains(name)) {
            return true;
        }
        SandboxTool tool = tools.get(name);
        if (tool == null) {
            throw new WorkerException(UNPROCESSABLE_ENTITY, "TOOL_NOT_SUPPORTED", "Worker 不支持该工具：" + name);
        }
        return tool.requiresRepository();
    }
}
