package qg.qgent.sandboxworker.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** 用于联调的模拟执行器，不会在宿主机上启动进程。 */
@Component
@ConditionalOnProperty(name = "sandbox.runtime", havingValue = "fake", matchIfMissing = true)
public class FakeCommandExecutor implements CommandExecutor {
    @Override
    public CommandExecutionResult execute(SandboxAllocation sandbox, String workingDirectory, List<String> command,
            Duration timeout)
            throws InterruptedException {
        if (command.contains("__timeout__")) {
            throw new InterruptedException("模拟执行超时");
        }
        if (command.contains("__wait_until_cancelled__")) {
            Thread.sleep(timeout.toMillis());
        }
        return new CommandExecutionResult(0, List.of("模拟执行完成：" + String.join(" ", command)), List.of());
    }
}
