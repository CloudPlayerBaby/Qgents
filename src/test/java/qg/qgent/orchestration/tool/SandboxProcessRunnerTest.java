package qg.qgent.orchestration.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SandboxProcessRunner 单元测试：用真实本地进程验证 stdout/stderr/exitCode 捕获、
 * 超时返回、启动失败与输出截断。命令按运行平台分支（Windows 用 cmd.exe / echo/ping，
 * 其余用 sh / printf / sleep）。不写任何 Secret。
 */
class SandboxProcessRunnerTest {

    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    private final SandboxProcessRunner runner = new SandboxProcessRunner();

    @Test
    void capturesStdoutAndZeroExitCode(@TempDir Path cwd) {
        ExecutionResult result = runner.run(cwd, stdoutCommand("hello"), Duration.ofSeconds(30));

        assertThat(result.ok()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("hello");
    }

    @Test
    void capturesStderrAndZeroExitCode(@TempDir Path cwd) {
        ExecutionResult result = runner.run(cwd, List.of(javaBin(), "-version"), Duration.ofSeconds(30));

        assertThat(result.ok()).isTrue();
        assertThat(result.exitCode()).isZero();
        assertThat(result.stderr()).contains("version");
    }

    @Test
    void reportsNonZeroExitCode(@TempDir Path cwd) {
        ExecutionResult result = runner.run(cwd, List.of(javaBin(), "-definitely-not-a-real-option"), Duration.ofSeconds(30));

        assertThat(result.ok()).isTrue();
        assertThat(result.exitCode()).isNotZero();
    }

    @Test
    void timesOutAndReturnsExplicitResult(@TempDir Path cwd) {
        ExecutionResult result = runner.run(cwd, sleepCommand(), Duration.ofMillis(500));

        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.error()).contains("timed out");
    }

    @Test
    void missingExecutableReportsLaunchFailure(@TempDir Path cwd) {
        ExecutionResult result = runner.run(cwd, List.of("definitely-not-a-real-command-xyz"), Duration.ofSeconds(5));

        assertThat(result.ok()).isFalse();
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.error()).contains("launch failed");
    }

    @Test
    void capsOversizedOutput(@TempDir Path cwd) {
        ExecutionResult result = runner.run(cwd, hugeOutputCommand(), Duration.ofSeconds(60));

        assertThat(result.ok()).isTrue();
        assertThat(result.stdout().length()).isLessThanOrEqualTo(MAX_OUTPUT_BYTES);
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString();
    }

    private static List<String> stdoutCommand(String text) {
        return isWindows() ? List.of("cmd.exe", "/c", "echo", text) : List.of("sh", "-c", "printf " + text);
    }

    private static List<String> sleepCommand() {
        // 直接启动睡眠进程，不经过 cmd/sh 包装，保证超时销毁时没有孙进程残留占用工作目录句柄
        return isWindows() ? List.of("ping", "-n", "20", "127.0.0.1") : List.of("sleep", "20");
    }

    private static List<String> hugeOutputCommand() {
        return isWindows()
                ? List.of("cmd.exe", "/c", "for /L %i in (1,1,100000) do @echo xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx")
                : List.of("sh", "-c", "yes xxxxxxxxxx | head -c 2000000");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("win");
    }
}
