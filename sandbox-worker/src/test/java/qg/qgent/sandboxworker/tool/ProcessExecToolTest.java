package qg.qgent.sandboxworker.tool;

import org.junit.jupiter.api.Test;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessExecToolTest {

    private final CommandExecutor executor = mock(CommandExecutor.class);
    private final ProcessExecTool tool = new ProcessExecTool(executor);

    @Test
    void allowsDevelopmentCommandsWithArguments() throws Exception {
        ToolContext context = context();
        when(executor.execute(any(), any(), any(), any()))
                .thenReturn(new CommandExecutionResult(0, List.of("ok"), List.of()));

        assertDoesNotThrow(() -> tool.execute(context, Map.of("command", List.of("gradle", "test", "--info"))));
        assertDoesNotThrow(() -> tool.execute(context, Map.of("command", List.of("mvn", "-DskipTests", "package"))));
        assertDoesNotThrow(() -> tool.execute(context, Map.of("command", List.of("npm", "run", "build"))));
        assertDoesNotThrow(() -> tool.execute(context, Map.of("command", List.of("pnpm", "run", "build"))));
        assertDoesNotThrow(() -> tool.execute(context, Map.of("command", List.of("node", "scripts/check.js"))));
        assertDoesNotThrow(() -> tool.execute(context, Map.of("command", List.of("python3", "scripts", "check.py"))));
        assertDoesNotThrow(() -> tool.execute(context, Map.of("command", List.of("make", "test"))));
        assertDoesNotThrow(() -> tool.execute(context, Map.of("command", List.of("sh", "./gradlew", "test"))));
        verify(executor).execute(any(), eq(context.getContainerRepository()), eq(List.of("gradle", "test", "--info")),
                eq(context.getTimeout()));
    }

    @Test
    void rejectsShellAndArbitraryScripts() {
        ToolContext context = context();

        assertThrows(WorkerException.class, () -> tool.execute(context,
                Map.of("command", List.of("sh", "-lc", "gradle test"))));
        assertThrows(WorkerException.class, () -> tool.execute(context,
                Map.of("command", List.of("bash", "-lc", "npm test"))));
        assertThrows(WorkerException.class, () -> tool.execute(context,
                Map.of("command", List.of("sh", "./build.sh"))));
        assertThrows(WorkerException.class, () -> tool.execute(context,
                Map.of("command", List.of("rm", "-rf", "."))));
        assertThrows(WorkerException.class, () -> tool.execute(context,
                Map.of("command", List.of("gradle", "-p", "/workspace/other", "test"))));
        assertThrows(WorkerException.class, () -> tool.execute(context,
                Map.of("command", List.of("npm", "run", "build;rm"))));
    }

    private ToolContext context() {
        return new ToolContext(mock(qg.qgent.sandboxworker.runtime.SandboxAllocation.class),
                null, null, "/workspace/repository", Duration.ofMinutes(5));
    }
}
