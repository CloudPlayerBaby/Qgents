package qg.qgent.orchestration.tool;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disabled 占位端口契约测试：任何 execute/diff 请求都必须返回明确 unavailable，
 * 绝不落到宿主机执行、不启动任何进程，也不伪造 base/head commit。
 */
class DisabledPortContractTest {

    private final UUID workspaceId = UUID.randomUUID();

    @Test
    void disabledExecutionPortAlwaysReturnsUnavailable() {
        DisabledExecutionPort port = new DisabledExecutionPort();

        ExecutionResult result = port.execute(workspaceId, List.of("mvn", "test"), Duration.ofMinutes(10));

        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.error()).contains("not available");
    }

    @Test
    void disabledExecutionPortRejectsEvenDangerousCommandsWithoutExecuting() {
        DisabledExecutionPort port = new DisabledExecutionPort();

        ExecutionResult result = port.execute(workspaceId, List.of("rm", "-rf", "/"), Duration.ofSeconds(5));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not available");
    }

    @Test
    void disabledWorkspaceDiffAccessAlwaysReturnsUnavailable() {
        DisabledWorkspaceDiffAccess access = new DisabledWorkspaceDiffAccess();

        GitDiffResult result = access.diff(workspaceId);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not available");
        assertThat(result.baseCommit()).isEmpty();
        assertThat(result.headCommit()).isEmpty();
    }
}
