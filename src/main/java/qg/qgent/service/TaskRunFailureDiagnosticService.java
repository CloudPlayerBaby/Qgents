package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskRunFailureDiagnosticEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.mapper.TaskRunFailureDiagnosticMapper;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.ExecutionContentSanitizer;
import qg.qgent.orchestration.OrchestrationPhase;
import qg.qgent.orchestration.RunOutcome;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 记录基础设施失败的受限诊断信息。
 *
 * <p>该服务仅由编排后端调用，不发布 SSE，也不向项目成员的执行产物写入失败详情。调用方必须在
 * TaskRun 进入终态前调用，保证诊断落库早于终态事件。每个 TaskRun 依靠唯一键仅保存一次，
 * 重试或并发重入会返回已存在的不可变记录。</p>
 */
@Service
public class TaskRunFailureDiagnosticService {
    private static final Logger log = LoggerFactory.getLogger(TaskRunFailureDiagnosticService.class);
    private static final int MAX_DETAIL_LENGTH = 4_096;
    private static final String DEFAULT_DETAIL = "未提供可记录的失败详情";

    private final TaskRunFailureDiagnosticMapper diagnostics;

    public TaskRunFailureDiagnosticService(TaskRunFailureDiagnosticMapper diagnostics) {
        this.diagnostics = diagnostics;
    }

    /**
     * 记录一次基础设施失败；非基础设施失败不会创建诊断记录。
     */
    @Transactional
    public TaskRunFailureDiagnosticEntity record(TaskEntity task, TaskRunEntity run, TaskStepEntity step,
                                                   OrchestrationPhase phase, AgentRunOutcome outcome) {
        if (outcome == null || outcome.getOutcome() != RunOutcome.FAILED_INFRASTRUCTURE) {
            return null;
        }
        validateOwnership(task, run, step);
        TaskRunFailureDiagnosticEntity existing = findByRunId(run.getId());
        if (existing != null) {
            return existing;
        }
        String rawCode = safeCode(firstNonBlank(outcome.getDiagnosticFailureCode(), outcome.getFailureCode()));
        String publicCode = ExecutionContentSanitizer.stableInfrastructureCode(outcome.getFailureCode());
        String detail = safeDetail(firstNonBlank(outcome.getDiagnosticDetail(), outcome.getMessage()));
        TaskRunFailureDiagnosticEntity entity = new TaskRunFailureDiagnosticEntity();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        entity.setId(UuidV7.next());
        entity.setProjectId(task.getProjectId());
        entity.setTaskId(task.getId());
        entity.setTaskRunId(run.getId());
        entity.setTaskStepId(step.getId());
        entity.setPhase(phase == null ? "UNKNOWN" : phase.name());
        entity.setSource(safeSource(outcome.getDiagnosticSource()));
        entity.setFailureCode(rawCode);
        entity.setPublicFailureCode(publicCode);
        entity.setExceptionType(safeExceptionType(outcome.getDiagnosticExceptionType()));
        entity.setFailureDetail(detail);
        entity.setDetailFingerprint(sha256(detail));
        entity.setOccurredAt(now);
        entity.setCreatedAt(now);
        try {
            diagnostics.insert(entity);
            log.info("task run failure diagnostic persisted taskRunId={} code={} publicCode={} source={} fingerprint={}",
                    run.getId(), rawCode, publicCode, entity.getSource(), entity.getDetailFingerprint());
            return entity;
        } catch (DuplicateKeyException ignored) {
            return findByRunId(run.getId());
        }
    }

    private TaskRunFailureDiagnosticEntity findByRunId(UUID taskRunId) {
        List<TaskRunFailureDiagnosticEntity> rows = diagnostics.selectList(
                Wrappers.<TaskRunFailureDiagnosticEntity>lambdaQuery()
                        .eq(TaskRunFailureDiagnosticEntity::getTaskRunId, taskRunId)
                        .last("LIMIT 1"));
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    private void validateOwnership(TaskEntity task, TaskRunEntity run, TaskStepEntity step) {
        if (task == null || run == null || step == null || task.getId() == null || task.getProjectId() == null
                || run.getId() == null || !task.getProjectId().equals(run.getProjectId())
                || !task.getId().equals(run.getTaskId()) || !task.getId().equals(step.getTaskId())
                || !step.getId().equals(run.getTaskStepId())) {
            throw new IllegalArgumentException("Task、TaskRun 与 TaskStep 的归属不一致");
        }
    }

    private String safeDetail(String value) {
        String detail = ExecutionContentSanitizer.sanitizeDiagnosticDetail(value == null ? "" : value).strip();
        if (detail.isEmpty()) {
            return DEFAULT_DETAIL;
        }
        return detail.length() <= MAX_DETAIL_LENGTH ? detail : detail.substring(0, MAX_DETAIL_LENGTH) + "…";
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "UNCLASSIFIED_INFRASTRUCTURE_FAILURE";
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z][A-Z0-9_]{0,63}") ? normalized : "UNCLASSIFIED_INFRASTRUCTURE_FAILURE";
    }

    private String safeSource(String value) {
        if (value == null || value.isBlank()) {
            return "AGENT_OUTCOME";
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z][A-Z0-9_]{0,31}") ? normalized : "UNKNOWN";
    }

    private String safeExceptionType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        return normalized.matches("[A-Za-z][A-Za-z0-9_$.]{0,254}") ? normalized : "UnknownException";
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 摘要算法", e);
        }
    }
}
