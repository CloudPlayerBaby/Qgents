package qg.qgent.sandboxworker.tool;

import org.junit.jupiter.api.Test;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 固定开发命令工具测试：请求不允许携带任意 argv，执行和记录中均不泄露 argv。 */
class DevelopmentRunToolTest {

    private final CommandExecutor executor = mock(CommandExecutor.class);
    private final DevelopmentRunTool tool = new DevelopmentRunTool(executor);

    @Test
    void executesOnlyFixedTemplateAndReturnsNoArgv() throws Exception {
        ToolContext context = context();
        when(executor.execute(any(), any(), any(), any()))
                .thenReturn(new CommandExecutionResult(0, List.of("ok"), List.of()));

        ToolResult result = tool.execute(context, Map.of("commandId", "MAVEN_WRAPPER_TEST"));

        verify(executor).execute(any(), eq(context.getContainerRepository()),
                eq(List.of("sh", "./mvnw", "test")), eq(context.getTimeout()));
        assertThat(result.getResult()).containsOnly(Map.entry("commandId", "MAVEN_WRAPPER_TEST"));
        assertThat(result.getResult()).doesNotContainKeys("command", "argv", "cwd", "environment");
    }

    @Test
    void rejectsUnknownOrRawCommandArguments() throws Exception {
        assertThatThrownBy(() -> tool.execute(context(), Map.of("commandId", "GIT_STATUS")))
                .isInstanceOf(WorkerException.class);
        assertThatThrownBy(() -> tool.execute(context(), Map.of("command", List.of("mvn", "test"))))
                .isInstanceOf(WorkerException.class);
        assertThatThrownBy(() -> tool.execute(context(), Map.of("commandId", "MAVEN_TEST", "args", List.of("-DskipTests"))))
                .isInstanceOf(WorkerException.class);
        verify(executor, never()).execute(any(), any(), any(), any());
    }

    @Test
    void redactsSensitiveOutputBeforeItCanBePersisted() throws Exception {
        when(executor.execute(any(), any(), any(), any())).thenReturn(new CommandExecutionResult(1,
                List.of("Authorization: Bearer secret-token", "{\"api_key\":\"abc123\"}",
                        "https://example.invalid/test?api_key=abc123", "TOKEN=abc123",
                        "at C:\\Users\\Administrator\\secret.txt"), List.of()));

        ToolResult result = tool.execute(context(), Map.of("commandId", "NPM_TEST"));

        assertThat(result.getStandardOutput()).containsExactly("Authorization=[redacted]", "{api_key=[redacted]}",
                "https://example.invalid/test?api_key=[redacted]", "TOKEN=[redacted]", "at [host path omitted]");
    }

    private ToolContext context() {
        return new ToolContext(mock(qg.qgent.sandboxworker.runtime.SandboxAllocation.class),
                null, null, "/workspace/repository", Duration.ofMinutes(5));
    }
}
