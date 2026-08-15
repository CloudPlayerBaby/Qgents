package qg.qgent.sandboxworker.runtime;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 使用 Docker Exec API 在既有沙箱容器内执行参数数组命令。
 * 命令不会经过 shell 拼接；输出受到本地字节上限保护。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sandbox.runtime", havingValue = "docker")
public class DockerCommandExecutor implements CommandExecutor {
    private final DockerClient docker;
    private final SandboxWorkerProperties properties;
    private final ConcurrentMap<String, ReentrantLock> sandboxCommandLocks = new ConcurrentHashMap<>();

    /**
     * 在沙箱内执行单条命令。超时或线程中断时会重启容器以终止残留进程，Workspace 挂载内容保持不变。
     */
    @Override
    public CommandExecutionResult execute(SandboxAllocation sandbox, String workingDirectory, List<String> command,
                                          Duration timeout)
            throws InterruptedException {
        if (sandbox.getRuntimeHandle() == null) {
            throw new IllegalStateException("沙箱缺少底层容器编号");
        }
        ReentrantLock lock = sandboxCommandLocks.computeIfAbsent(sandbox.getRuntimeHandle(), ignored ->
                new ReentrantLock());
        lock.lockInterruptibly();
        try {
            return executeLocked(sandbox, workingDirectory, command, timeout);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在同一容器命令锁内执行 Docker Exec。
     * 取消或超时需要重启容器，因此同一 Sandbox 内的容器命令必须串行，避免误杀其他正在运行的命令。
     */
    private CommandExecutionResult executeLocked(SandboxAllocation sandbox, String workingDirectory,
                                                 List<String> command, Duration timeout) throws InterruptedException {
        String execId = docker.execCreateCmd(sandbox.getRuntimeHandle())
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withWorkingDir(workingDirectory)
                .withCmd(command.toArray(String[]::new))
                .exec().getId();
        LimitedOutputStream stdout = new LimitedOutputStream(properties.getMaxOutputBytes());
        LimitedOutputStream stderr = new LimitedOutputStream(properties.getMaxOutputBytes());
        try (ExecStartResultCallback callback = new ExecStartResultCallback(stdout, stderr)) {
            boolean completed = docker.execStartCmd(execId).exec(callback)
                    .awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                throw new InterruptedException("Docker Exec 执行超时");
            }
        } catch (InterruptedException exception) {
            restartContainer(sandbox.getRuntimeHandle());
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Docker Exec 执行失败", exception);
        }
        Long exitCode = docker.inspectExecCmd(execId).exec().getExitCodeLong();
        return new CommandExecutionResult(exitCode == null ? -1 : exitCode.intValue(), lines(stdout), lines(stderr));
    }

    private void restartContainer(String containerId) {
        try {
            docker.killContainerCmd(containerId).exec();
            docker.startContainerCmd(containerId).exec();
        } catch (RuntimeException exception) {
            throw new IllegalStateException("终止超时命令后无法恢复沙箱容器", exception);
        }
    }

    private List<String> lines(LimitedOutputStream output) {
        String value = output.value();
        if (output.isTruncated()) {
            value += System.lineSeparator() + "[输出超过限制，已截断]";
        }
        return value.isEmpty() ? List.of() : Arrays.asList(value.split("\\R"));
    }

    private static final class LimitedOutputStream extends OutputStream {
        private final int limit;
        private final ByteArrayOutputStream delegate;
        private boolean truncated;

        private LimitedOutputStream(int limit) {
            this.limit = limit;
            this.delegate = new ByteArrayOutputStream(Math.min(limit, 8192));
        }

        @Override
        public void write(int value) {
            if (delegate.size() < limit) {
                delegate.write(value);
            } else {
                truncated = true;
            }
        }

        @Override
        public void write(byte[] values, int offset, int length) throws IOException {
            int writable = Math.min(length, Math.max(0, limit - delegate.size()));
            if (writable > 0) {
                delegate.write(values, offset, writable);
            }
            truncated |= writable < length;
        }

        private String value() {
            return delegate.toString(StandardCharsets.UTF_8);
        }

        private boolean isTruncated() {
            return truncated;
        }
    }
}
