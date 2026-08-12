package qg.qgent.orchestration.tool;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.service.WorkspaceService;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LocalSandboxExecutionPort 纯单元测试：Mock WorkspaceService 与 SandboxProcessRunner，
 * 白名单为真实策略。覆盖危险命令拦截、cwd/timeout 正确传递、Workspace 未就绪/不存在明确
 * 失败、WorkspaceService 异常向上传播，以及平台 argv 包装。不执行任何宿主机命令。
 */
class LocalSandboxExecutionPortTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final SandboxProcessRunner runner = mock(SandboxProcessRunner.class);
    private final UUID workspaceId = UUID.randomUUID();

    private LocalSandboxExecutionPort port() {
        return new LocalSandboxExecutionPort(workspaceService, runner);
    }

    @Test
    void dangerousCommandIsRejectedBeforeWorkspaceLookupAndRunning() {
        ExecutionResult result = port().execute(workspaceId, List.of("rm", "-rf", "/"), Duration.ofSeconds(5));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not allowed");
        verify(workspaceService, never()).resolve(any());
        verify(runner, never()).run(any(), anyList(), any());
    }

    @Test
    void whitelistedCommandRunsInWorkspaceRootWithTimeout() {
        Path root = Path.of("C:/tmp/ws-1");
        when(workspaceService.resolve(workspaceId)).thenReturn(new WorkspaceService.WorkspaceResolution(true, true, root, null));
        ExecutionResult expected = new ExecutionResult(true, 0, "BUILD SUCCESS", "", null);
        when(runner.run(any(), anyList(), any())).thenReturn(expected);
        Duration timeout = Duration.ofMinutes(10);

        ExecutionResult result = port().execute(workspaceId, List.of("mvn", "test"), timeout);

        assertThat(result).isEqualTo(expected);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> argv = ArgumentCaptor.forClass(List.class);
        verify(runner).run(eq(root), argv.capture(), eq(timeout));
        assertThat(argv.getValue()).containsAll(List.of("mvn", "test"));
    }

    @Test
    void timeoutResultIsPropagatedVerbatim() {
        Path root = Path.of("C:/tmp/ws-1");
        when(workspaceService.resolve(workspaceId)).thenReturn(new WorkspaceService.WorkspaceResolution(true, true, root, null));
        ExecutionResult timeout = new ExecutionResult(false, -1, "partial stdout", "partial stderr",
                "process timed out after PT10M");
        when(runner.run(any(), anyList(), any())).thenReturn(timeout);
        Duration timeoutDuration = Duration.ofMinutes(10);

        ExecutionResult result = port().execute(workspaceId, List.of("mvn", "test"), timeoutDuration);

        assertThat(result).isEqualTo(timeout);
        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.error()).contains("timed out");
        // 端口把 runner 的超时结果原样透传，cwd 与 timeout 正确下发，不吞、不改写。
        verify(runner).run(eq(root), any(), eq(timeoutDuration));
    }

    @Test
    void workspaceNotReadyFailsExplicitlyWithoutRunning() {
        Path root = Path.of("C:/tmp/ws-1");
        when(workspaceService.resolve(workspaceId))
                .thenReturn(new WorkspaceService.WorkspaceResolution(true, false, root, "workspace directory not present: C:/tmp/ws-1"));

        ExecutionResult result = port().execute(workspaceId, List.of("mvn", "test"), Duration.ofSeconds(5));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not ready");
        verify(runner, never()).run(any(), anyList(), any());
    }

    @Test
    void workspaceNotFoundFailsExplicitlyWithoutRunning() {
        when(workspaceService.resolve(workspaceId))
                .thenReturn(new WorkspaceService.WorkspaceResolution(false, false, null, "workspace not found: " + workspaceId));

        ExecutionResult result = port().execute(workspaceId, List.of("mvn", "test"), Duration.ofSeconds(5));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not ready");
        verify(runner, never()).run(any(), anyList(), any());
    }

    @Test
    void serviceFailurePropagatesToCaller() {
        when(workspaceService.resolve(workspaceId)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> port().execute(workspaceId, List.of("mvn", "test"), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }

    @Test
    void launchArgvIsWrappedOnWindowsForBatchEntryOnly() {
        List<String> argv = LocalSandboxExecutionPort.wrapForLaunch(List.of("mvn", "test"));

        if (isWindows()) {
            assertThat(argv).containsExactly("cmd.exe", "/c", "mvn", "test");
        } else {
            assertThat(argv).containsExactly("mvn", "test");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");
    }
}
