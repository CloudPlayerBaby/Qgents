package qg.qgent.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskRunWorkerExecutionEntity;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.TaskRunWorkerExecutionMapper;
import qg.qgent.orchestration.ExecutionContentSanitizer;
import qg.qgent.orchestration.worker.WorkerToolExecution;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 在 Worker 接收工具执行后立即持久化 TaskRun 关联，并在轮询到终态后更新受限诊断字段。
 */
@Service
public class TaskRunWorkerExecutionService {
    private final TaskRunWorkerExecutionMapper executions;
    private final TaskRunMapper runs;

    public TaskRunWorkerExecutionService(TaskRunWorkerExecutionMapper executions, TaskRunMapper runs) {
        this.executions = executions;
        this.runs = runs;
    }

    /**
     * 幂等记录 Worker 回执。调用点位于提交成功后和轮询终态后，因此进程在轮询期间中断时
     * 仍保留 executionId 与其所属 TaskRun。
     */
    @Transactional
    public void record(UUID taskRunId, WorkerToolExecution execution) {
        if (taskRunId == null || execution == null || execution.getId() == null) {
            return;
        }
        TaskRunEntity run = runs.selectById(taskRunId);
        if (run == null) {
            return;
        }
        TaskRunWorkerExecutionEntity stored = executions.selectById(execution.getId());
        boolean insert = stored == null;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (insert) {
            stored = new TaskRunWorkerExecutionEntity();
            stored.setExecutionId(execution.getId());
            stored.setProjectId(run.getProjectId());
            stored.setTaskId(run.getTaskId());
            stored.setTaskRunId(run.getId());
            stored.setCreatedAt(timestamp(execution.getCreatedAt(), now));
        }
        // 同一个 executionId 只能属于创建它的 TaskRun，禁止跨项目覆盖关联。
        if (!run.getId().equals(stored.getTaskRunId())) {
            return;
        }
        stored.setSandboxId(execution.getSandboxId());
        stored.setRepositoryId(execution.getRepositoryId());
        stored.setToolName(limit(execution.getTool(), 64));
        stored.setStatus(limit(execution.getStatus(), 24));
        stored.setExitCode(execution.getExitCode());
        stored.setFailureCode(stableCode(execution.getFailureCode()));
        stored.setFailureReason(safeReason(stored.getFailureCode()));
        stored.setStartedAt(timestamp(execution.getStartedAt(), stored.getStartedAt()));
        stored.setFinishedAt(timestamp(execution.getFinishedAt(), stored.getFinishedAt()));
        stored.setUpdatedAt(now);
        if (insert) {
            executions.insert(stored);
        } else {
            executions.updateById(stored);
        }
    }

    private LocalDateTime timestamp(String value, LocalDateTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private String stableCode(String value) {
        String publicCode = ExecutionContentSanitizer.publicFailureCode(value);
        return publicCode == null && value != null && !value.isBlank() ? "FAILED_INFRASTRUCTURE" : publicCode;
    }

    private String safeReason(String publicFailureCode) {
        return publicFailureCode == null ? null : ExecutionContentSanitizer.userFailureDescription(publicFailureCode);
    }

    private String limit(String value, int max) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max) + "…";
    }
}
