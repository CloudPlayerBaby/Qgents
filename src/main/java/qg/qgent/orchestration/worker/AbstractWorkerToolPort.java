package qg.qgent.orchestration.worker;

import qg.qgent.auth.UuidV7;
import qg.qgent.service.TaskRunWorkerExecutionService;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Worker 工具类端口的公共基类：负责把"提交工具执行 → 轮询终态 → 取结果"这条
 * 异步链路收敛为一次同步调用。子类只需关心工具参数构造与结果形状翻译。
 * <p>
 * 不是 Spring Bean，仅被同包的 Worker 端口实现继承。
 */
abstract class AbstractWorkerToolPort {

    protected final SandboxWorkerClient client;
    protected final SandboxSessionManager sessions;
    protected final SandboxWorkerProperties properties;
    private final TaskRunWorkerExecutionService workerExecutionService;

    private final java.util.function.Supplier<Long> nanoTimeSupplier;

    AbstractWorkerToolPort(SandboxWorkerClient client, SandboxSessionManager sessions,
                           SandboxWorkerProperties properties) {
        this(client, sessions, properties, null, System::nanoTime);
    }

    AbstractWorkerToolPort(SandboxWorkerClient client, SandboxSessionManager sessions,
                           SandboxWorkerProperties properties, java.util.function.Supplier<Long> nanoTimeSupplier) {
        this(client, sessions, properties, null, nanoTimeSupplier);
    }

    AbstractWorkerToolPort(SandboxWorkerClient client, SandboxSessionManager sessions,
                           SandboxWorkerProperties properties, TaskRunWorkerExecutionService workerExecutionService) {
        this(client, sessions, properties, workerExecutionService, System::nanoTime);
    }

    AbstractWorkerToolPort(SandboxWorkerClient client, SandboxSessionManager sessions,
                           SandboxWorkerProperties properties, TaskRunWorkerExecutionService workerExecutionService,
                           java.util.function.Supplier<Long> nanoTimeSupplier) {
        this.client = client;
        this.sessions = sessions;
        this.properties = properties;
        this.workerExecutionService = workerExecutionService;
        this.nanoTimeSupplier = nanoTimeSupplier == null ? System::nanoTime : nanoTimeSupplier;
    }

    /**
     * 解析当前 Workspace 的会话；未启用或未创建时抛错。
     */
    protected SandboxSession session(UUID workspaceId) {
        return sessions.require(workspaceId);
    }

    /**
     * 提交一次工具执行并阻塞等待终态。
     */
    protected WorkerToolExecution executeTool(UUID workspaceId, UUID repositoryId, String tool,
                                              Map<String, Object> arguments, Duration timeout) {
        SandboxSession session = session(workspaceId);
        // 先续持久 Workspace 写租约，再续 Sandbox；二者都成功后才允许提交可能改动文件的工具。
        // 固定开发命令当前仍可能生成测试报告或构建缓存，因此保守地走同一护栏。
        sessions.renewWriteLease(workspaceId);
        UUID sandboxId = session.sandboxId();
        client.renewSandbox(sandboxId);

        WorkerToolExecutionRequest request = new WorkerToolExecutionRequest();
        request.setExecutionId(UuidV7.next());
        request.setRepositoryId(repositoryId);
        request.setTool(tool);
        request.setArguments(arguments);
        request.setTimeoutSeconds(timeout == null ? null : Math.max(1, timeout.toSeconds()));
        WorkerToolExecution submitted = client.submitToolExecution(sandboxId, request);
        // 提交成功后立即记录 ID：即使后续轮询超时或线程被取消，运维仍可定位 Worker 日志。
        WorkerExecutionTraceContext.record(submitted);
        persistDiagnostic(submitted);
        WorkerToolExecution execution = pollUntilTerminal(sandboxId, submitted.getId());
        // 同一 ID 的终态会覆盖 QUEUED/RUNNING 摘要。
        WorkerExecutionTraceContext.record(execution);
        persistDiagnostic(execution);
        return execution;
    }

    private void persistDiagnostic(WorkerToolExecution execution) {
        UUID taskRunId = WorkerExecutionTraceContext.currentTaskRunId();
        if (workerExecutionService != null && taskRunId != null) {
            workerExecutionService.record(taskRunId, execution);
        }
    }

    private WorkerToolExecution pollUntilTerminal(UUID sandboxId, UUID executionId) {
        long deadline = nanoTimeSupplier.get() + properties.getPollTimeout().toNanos();
        long renewIntervalNanos = properties.leaseRenewInterval().toNanos();
        long nextRenewAt = nanoTimeSupplier.get() + renewIntervalNanos;

        while (true) {
            WorkerToolExecution execution = client.getToolExecution(executionId);
            if (execution == null) {
                throw new IllegalStateException("tool execution not found: " + executionId);
            }
            if (isTerminal(execution.getStatus())) {
                return execution;
            }
            long now = nanoTimeSupplier.get();
            if (now >= deadline) {
                throw new IllegalStateException("tool execution did not finish within poll timeout: " + executionId);
            }
            if (now >= nextRenewAt) {
                client.renewSandbox(sandboxId);
                nextRenewAt = now + renewIntervalNanos;
            }
            sleep(properties.getPollInterval());
        }
    }

    private static boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "TIMED_OUT".equals(status)
                || "CANCELLED".equals(status) || "INTERRUPTED".equals(status);
    }

    private static void sleep(Duration interval) {
        try {
            Thread.sleep(Math.max(1, interval.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling tool execution", e);
        }
    }

    /**
     * 工具执行结果 map（终态失败时 Worker 返回空 map，故空安全）。
     */
    protected static Map<String, Object> resultOf(WorkerToolExecution execution) {
        return execution.getResult() == null ? Map.of() : execution.getResult();
    }
}
