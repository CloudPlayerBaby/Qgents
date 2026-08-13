package qg.qgent.orchestration.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import qg.qgent.orchestration.tool.ExecutionPort;
import qg.qgent.orchestration.tool.ExecutionResult;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link ExecutionPort} 的 Worker 实现：通过 Worker 的 {@code process.exec} 在沙箱内执行命令，
 * 并以真实 exitCode 判定结果，替代主后端宿主机进程执行。
 * <p>
 * Worker 工具执行是异步的（202 入队后轮询），stdout/stderr 存于执行日志，本类把"提交 → 轮询
 * 终态 → 拉取 STDOUT/STDERR 日志"收敛为一次同步 {@link ExecutionResult}：
 * exitCode 非空表示命令真实执行（ok=true，即使退出码非零），否则为基础设施/超时失败（ok=false）。
 * <p>
 * 命令在沙箱内执行，由 Worker 隔离边界保证安全，不再需要主后端的宿主机白名单。
 * {@code app.worker.enabled=true} 时作为默认 ExecutionPort 启用。
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class WorkerSandboxExecutionPort extends AbstractWorkerToolPort implements ExecutionPort {

    public WorkerSandboxExecutionPort(SandboxWorkerClient client, SandboxSessionManager sessions,
            SandboxWorkerProperties properties) {
        super(client, sessions, properties);
    }

    @Override
    public ExecutionResult execute(UUID workspaceId, List<String> command, Duration timeout) {
        if (command == null || command.isEmpty()) {
            return new ExecutionResult(false, -1, "", "", "command must not be empty");
        }
        try {
            UUID repositoryId = session(workspaceId).singleRepository();
            WorkerToolExecution execution = executeTool(workspaceId, repositoryId, "process.exec",
                    Map.of("command", command), timeout);
            String stdout = collectLogs(execution.getId(), "STDOUT");
            String stderr = collectLogs(execution.getId(), "STDERR");
            Integer exitCode = execution.getExitCode();
            if (exitCode == null) {
                return new ExecutionResult(false, -1, stdout, stderr,
                        execution.getFailureReason() == null ? "process execution failed" : execution.getFailureReason());
            }
            return new ExecutionResult(true, exitCode, stdout, stderr, null);
        } catch (RuntimeException e) {
            return new ExecutionResult(false, -1, "", "", e.getMessage());
        }
    }

    /** 按游标拉取指定流的全部日志并合并为文本。 */
    private String collectLogs(UUID executionId, String stream) {
        StringBuilder buffer = new StringBuilder();
        long after = 0;
        while (true) {
            WorkerExecutionLogs logs = client.getToolExecutionLogs(executionId, after, 1000);
            if (logs == null || logs.getItems() == null || logs.getItems().isEmpty()) {
                break;
            }
            for (WorkerExecutionLogEntry entry : logs.getItems()) {
                if (stream.equals(entry.getStream())) {
                    if (buffer.length() > 0) {
                        buffer.append('\n');
                    }
                    buffer.append(entry.getContent());
                }
            }
            long next = logs.getNextCursor();
            if (next <= after) {
                break;
            }
            after = next;
        }
        return buffer.toString();
    }
}
