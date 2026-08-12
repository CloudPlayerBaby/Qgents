package qg.qgent.sandboxworker.runtime;

import java.time.Duration;
import java.util.List;

/**
 * 沙箱内参数数组命令执行器。
 * 实现不得通过 shell 拼接命令，并必须遵守调用方给出的超时时间。
 */
public interface CommandExecutor {

    /**
     * @param sandbox 目标沙箱
     * @param workingDirectory 容器内受控工作目录
     * @param command 可执行文件和参数组成的数组
     * @param timeout 最大执行时间
     * @return 退出码以及受大小限制的标准输出和错误输出
     * @throws InterruptedException 命令超时或执行线程被取消时抛出
     */
    CommandExecutionResult execute(
            SandboxAllocation sandbox,
            String workingDirectory,
            List<String> command,
            Duration timeout) throws InterruptedException;
}
