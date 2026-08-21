package qg.qgent.orchestration.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import qg.qgent.api.ApiException;
import qg.qgent.orchestration.ExecutionContentSanitizer;
import qg.qgent.orchestration.tool.ExecutionPort;
import qg.qgent.orchestration.tool.ExecutionResult;
import qg.qgent.service.TaskRunWorkerExecutionService;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link ExecutionPort} 的 Worker 兼容实现：只将既有测试白名单映射为 Worker 的固定
 * {@code development.run} commandId，绝不向 Worker 传递原始 argv。
 * <p>
 * Worker 工具执行是异步的（202 入队后轮询）。仅为旧 TestAgent 的环境/质量分流，本类读取
 * {@code development.run} 已脱敏日志，并在主端再次脱敏、头尾限长后返回；Coding Agent 端口不读取
 * stdout/stderr。exitCode 非空表示命令真实执行（ok=true，即使退出码非零）。
 * <p>
 * 命令在沙箱内执行，由 Worker 隔离边界保证安全，不再需要主后端的宿主机白名单。
 * {@code app.worker.enabled=true} 时作为默认 ExecutionPort 启用。
 */
@Component
@Primary
@Slf4j
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class WorkerSandboxExecutionPort extends AbstractWorkerToolPort implements ExecutionPort {

    private static final int MAX_TEST_DIAGNOSTIC_CHARS = 24_000;

    /** 兼容无 Spring 容器的端口单元测试；生产装配使用带诊断持久化服务的构造器。 */
    public WorkerSandboxExecutionPort(SandboxWorkerClient client, SandboxSessionManager sessions,
                                      SandboxWorkerProperties properties) {
        super(client, sessions, properties);
    }

    @Autowired
    public WorkerSandboxExecutionPort(SandboxWorkerClient client, SandboxSessionManager sessions,
                                      SandboxWorkerProperties properties,
                                      TaskRunWorkerExecutionService workerExecutionService) {
        super(client, sessions, properties, workerExecutionService);
    }

    @Override
    public ExecutionResult execute(UUID workspaceId, List<String> command, Duration timeout) {
        return execute(workspaceId, null, command, timeout);
    }

    @Override
    public ExecutionResult execute(UUID workspaceId, String repositoryPath, List<String> command, Duration timeout) {
        if (command == null || command.isEmpty()) {
            return new ExecutionResult(false, -1, "", "", "command must not be empty");
        }
        UUID repositoryId = null;
        try {
            SandboxSession current = session(workspaceId);
            if (repositoryPath == null || repositoryPath.isBlank()) {
                repositoryId = current.singleRepository();
            } else {
                repositoryId = current.repositoryByPath().get(repositoryPath);
                if (repositoryId == null) {
                    return new ExecutionResult(false, -1, "", "", "repository path is not part of the workspace");
                }
            }
            String commandId = developmentCommandId(command);
            if (commandId == null) {
                return new ExecutionResult(false, -1, "", "", "command not allowed by fixed development catalog");
            }
            WorkerToolExecution execution = executeTool(workspaceId, repositoryId, "development.run",
                    Map.of("commandId", commandId), timeout);
            Integer exitCode = execution.getExitCode();
            // 仅旧 TestAgent 兼容端口读取 development.run 已脱敏日志，供环境/质量分流；
            // Coding Agent 的 DevelopmentCommandPort 明确不读取日志。主端再次脱敏并限制长度。
            String stdout = collectDiagnosticLogs(execution.getId(), "STDOUT");
            String stderr = collectDiagnosticLogs(execution.getId(), "STDERR");
            if (exitCode == null) {
                log.warn("FIXED_DEVELOPMENT_COMMAND_FAILED workspaceId={} repositoryId={} executionId={} status={}",
                        workspaceId, repositoryId, execution.getId(), execution.getStatus());
                return new ExecutionResult(false, -1, stdout, stderr, "fixed development command failed");
            }
            return new ExecutionResult(true, exitCode, stdout, stderr, null);
        } catch (RuntimeException e) {
            // 保留具体失败原因（如 Sandbox 被清理的 404），供 Test/Review 判断失败是否环境所致；
            // 仅错误文案，不含命令输出，无需脱敏。executionId 缺失时仍按执行失败而非异常返回。
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (e instanceof ApiException api) {
                reason = api.code() + ": " + reason;
            }
            log.warn("FIXED_DEVELOPMENT_COMMAND_INFRASTRUCTURE workspaceId={} repositoryId={} category={} reason={}",
                    workspaceId, repositoryId, e.getClass().getSimpleName(), reason);
            return new ExecutionResult(false, -1, "", "", reason);
        }
    }

    private String developmentCommandId(List<String> command) {
        if (List.of("mvn", "test").equals(command)) return "MAVEN_TEST";
        if (List.of("gradle", "test").equals(command)) return "GRADLE_TEST";
        if (List.of("sh", "./mvnw", "test").equals(command)) return "MAVEN_WRAPPER_TEST";
        if (List.of("sh", "./gradlew", "test").equals(command)) return "GRADLE_WRAPPER_TEST";
        if (List.of("npm", "test").equals(command)) return "NPM_TEST";
        return null;
    }

    private String collectDiagnosticLogs(UUID executionId, String stream) {
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
                    buffer.append(entry.getContent() == null ? "" : entry.getContent());
                }
            }
            long next = logs.getNextCursor();
            if (next <= after) {
                break;
            }
            after = next;
        }
        return limitDiagnostic(ExecutionContentSanitizer.sanitizeDiagnosticDetail(buffer.toString()));
    }

    private String limitDiagnostic(String value) {
        String text = value == null ? "" : value;
        if (text.length() <= MAX_TEST_DIAGNOSTIC_CHARS) {
            return text;
        }
        String marker = "\n...[已裁剪]...\n";
        int remaining = MAX_TEST_DIAGNOSTIC_CHARS - marker.length();
        return text.substring(0, (remaining + 1) / 2) + marker
                + text.substring(text.length() - remaining / 2);
    }
}
