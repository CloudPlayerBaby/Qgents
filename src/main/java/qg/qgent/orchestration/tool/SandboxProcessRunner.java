package qg.qgent.orchestration.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本地受限进程运行器：在工作区根目录内以独立 argv 启动进程，并发捕获 stdout/stderr、
 * 限制单流输出大小并实施超时。本类是项目中唯一允许使用 {@link ProcessBuilder} 的地方。
 * <p>
 * 安全与约束：
 * <ul>
 *   <li>只执行调用方传入的 argv，不做任何 shell 字符串拼接；进程工作目录由调用方指定，本类不解析路径；</li>
 *   <li>stdout / stderr 分流读取（redirectErrorStream=false），UTF-8 解码，单流上限 1MB 防止输出膨胀占满内存；</li>
 *   <li>超时后强制销毁进程并返回明确的超时结果（含已捕获的 partial 输出），不无限等待；</li>
 *   <li>启动失败（命令不存在等）返回明确的 launch failed 结果，不抛异常。</li>
 * </ul>
 * 本类不感知命令白名单；白名单由 {@link SandboxCommandPolicy} 在端口层强制执行。
 */
class SandboxProcessRunner {

    /** 单流 stdout/stderr 捕获上限（字节）。 */
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    /**
     * 在指定工作目录内执行命令并收集结果。
     *
     * @param cwd     进程工作目录（由端口依据 WorkspaceService 解析，本类不校验其合法性）。
     * @param command 完整启动 argv（端口已完成白名单校验与平台包装）。
     * @param timeout 执行超时；超时后进程被强制销毁。
     * @return 执行结果：正常结束 ok=true + 真实 exitCode/stdout/stderr；启动失败或超时
     *         ok=false + exitCode=-1 + 明确 error。
     */
    ExecutionResult run(Path cwd, List<String> command, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(false);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new ExecutionResult(false, -1, "", "", "process launch failed: " + e.getMessage());
        }

        StringBuffer stdout = new StringBuffer();
        StringBuffer stderr = new StringBuffer();
        Thread out = pump(process.getInputStream(), stdout, "stdout");
        Thread err = pump(process.getErrorStream(), stderr, "stderr");
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                joinQuietly(out, 1000);
                joinQuietly(err, 1000);
                return new ExecutionResult(false, -1, stdout.toString(), stderr.toString(),
                        "process timed out after " + timeout);
            }
            joinQuietly(out, 2000);
            joinQuietly(err, 2000);
            return new ExecutionResult(true, process.exitValue(), stdout.toString(), stderr.toString(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ExecutionResult(false, -1, stdout.toString(), stderr.toString(),
                    "process wait interrupted: " + e.getMessage());
        }
    }

    /** 启动一个守护线程把流读到上限为止，返回该线程供调用方 join。 */
    private static Thread pump(InputStream stream, StringBuffer buffer, String label) {
        Thread thread = new Thread(() -> read(stream, buffer), "sandbox-output-" + label);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** 流读取：UTF-8 分块解码并追加到缓冲区，字节数到达上限后停止并关闭流。 */
    private static void read(InputStream stream, StringBuffer buffer) {
        byte[] chunk = new byte[4096];
        int appendedBytes = 0;
        try (stream) {
            int n;
            while ((n = stream.read(chunk)) != -1) {
                int take = Math.min(n, MAX_OUTPUT_BYTES - appendedBytes);
                if (take <= 0) {
                    break;
                }
                buffer.append(new String(chunk, 0, take, StandardCharsets.UTF_8));
                appendedBytes += take;
            }
        } catch (IOException ignored) {
            // 进程被销毁或流关闭时结束读取，不以流错误推翻执行结果
        }
    }

    private static void joinQuietly(Thread thread, long millis) {
        try {
            thread.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
