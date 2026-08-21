package qg.qgent.orchestration.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import qg.qgent.orchestration.tool.DevelopmentCommandId;
import qg.qgent.orchestration.tool.DevelopmentCommandPort;
import qg.qgent.orchestration.tool.DevelopmentCommandResult;
import qg.qgent.service.TaskRunWorkerExecutionService;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 通过 Worker {@code development.run} 执行 Coding Agent 的固定开发命令。
 * <p>
 * 请求只携带 commandId，仓库由当前 Workspace SandboxSession 绑定；本端不读取 Worker 日志，
 * 以免 stdout/stderr、argv 或构建环境细节回流到模型和 TaskRun。
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class WorkerDevelopmentCommandPort extends AbstractWorkerToolPort implements DevelopmentCommandPort {

    public WorkerDevelopmentCommandPort(SandboxWorkerClient client, SandboxSessionManager sessions,
                                        SandboxWorkerProperties properties) {
        super(client, sessions, properties);
    }

    /** 生产装配保留受控执行记录（executionId、工具名、状态、exitCode），不持久化输出。 */
    @Autowired
    public WorkerDevelopmentCommandPort(SandboxWorkerClient client, SandboxSessionManager sessions,
                                        SandboxWorkerProperties properties,
                                        TaskRunWorkerExecutionService workerExecutionService) {
        super(client, sessions, properties, workerExecutionService);
    }

    @Override
    public DevelopmentCommandResult run(UUID workspaceId, String repositoryPath, DevelopmentCommandId commandId) {
        if (workspaceId == null || commandId == null) {
            return DevelopmentCommandResult.unavailable(commandId);
        }
        UUID repositoryId = null;
        try {
            repositoryId = repositoryId(workspaceId, repositoryPath);
            if (repositoryId == null) {
                return new DevelopmentCommandResult(false, commandId.name(), null,
                        "WORKSPACE_REPOSITORY_NOT_FOUND", "未找到工作区绑定的仓库");
            }
            WorkerToolExecution execution = executeTool(workspaceId, repositoryId, "development.run",
                    Map.of("commandId", commandId.name()), timeout(commandId));
            if (execution.getExitCode() != null) {
                return new DevelopmentCommandResult(execution.getExitCode() == 0, commandId.name(),
                        execution.getExitCode(), execution.getExitCode() == 0 ? null : "PROCESS_EXIT_NONZERO",
                        execution.getExitCode() == 0 ? null : "固定开发命令执行失败");
            }
            return new DevelopmentCommandResult(false, commandId.name(), null,
                    safeCode(), safeReason());
        } catch (RuntimeException exception) {
            log.warn("CODING_DEVELOPMENT_COMMAND_FAILED workspaceId={} repositoryId={} commandId={} category={}",
                    workspaceId, repositoryId, commandId, exception.getClass().getSimpleName());
            return new DevelopmentCommandResult(false, commandId.name(), null,
                    "DEVELOPMENT_COMMAND_INFRASTRUCTURE", "固定开发命令执行基础设施不可用");
        }
    }

    private UUID repositoryId(UUID workspaceId, String repositoryPath) {
        SandboxSession current = session(workspaceId);
        if (repositoryPath == null || repositoryPath.isBlank()) {
            return current.singleRepository();
        }
        return current.repositoryByPath() == null ? null : current.repositoryByPath().get(repositoryPath);
    }

    private Duration timeout(DevelopmentCommandId commandId) {
        return switch (commandId) {
            case MAVEN_TEST, MAVEN_PACKAGE, MAVEN_WRAPPER_TEST, GRADLE_TEST, GRADLE_WRAPPER_TEST, NPM_TEST ->
                    Duration.ofMinutes(10);
        };
    }

    private String safeCode() {
        return "DEVELOPMENT_COMMAND_FAILED";
    }

    private String safeReason() {
        return "固定开发命令执行失败，请查看受控执行记录";
    }
}
