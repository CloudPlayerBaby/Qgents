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
import qg.qgent.orchestration.TaskStepExecutionMode;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 记录失败 Run 的受限诊断信息。
 *
 * <p>该服务仅由编排后端调用，不发布 SSE，也不向项目成员的执行产物写入未受控失败详情。调用方必须在
 * TaskRun 进入终态前调用，保证诊断落库早于终态事件。每个 TaskRun 依靠唯一键仅保存一次，
 * 重试或并发重入会返回已存在的不可变记录。</p>
 */
@Service
public class TaskRunFailureDiagnosticService {
    private static final Logger log = LoggerFactory.getLogger(TaskRunFailureDiagnosticService.class);
    private static final int MAX_DETAIL_LENGTH = 4_096;
    /** 诊断 JSON 上下文总量通过少量、短文本失败项控制在约 8 KB 内。 */
    private static final int MAX_CONTEXT_ITEMS = 4;
    private static final int MAX_CONTEXT_TEXT_LENGTH = 384;
    private static final String DEFAULT_DETAIL = "未提供可记录的失败详情";

    private final TaskRunFailureDiagnosticMapper diagnostics;

    public TaskRunFailureDiagnosticService(TaskRunFailureDiagnosticMapper diagnostics) {
        this.diagnostics = diagnostics;
    }

    /**
     * 记录一次失败 Run。取消和成功不创建记录；每条记录都关联触发失败的具体 Step。
     */
    @Transactional
    public TaskRunFailureDiagnosticEntity record(TaskEntity task, TaskRunEntity run, TaskStepEntity step,
                                                   OrchestrationPhase phase, AgentRunOutcome outcome) {
        if (!isFailed(outcome)) {
            return null;
        }
        validateOwnership(task, run, step);
        TaskRunFailureDiagnosticEntity existing = findByRunId(run.getId());
        if (existing != null) {
            return existing;
        }
        String rawCode = safeCode(firstNonBlank(outcome.getDiagnosticFailureCode(), outcome.getFailureCode(),
                derivedFailureCode(outcome)));
        String publicCode = publicFailureCode(outcome, rawCode);
        String detail = safeDetail(firstNonBlank(outcome.getDiagnosticDetail(), outcome.getMessage()));
        TaskRunFailureDiagnosticEntity entity = new TaskRunFailureDiagnosticEntity();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        entity.setId(UuidV7.next());
        entity.setProjectId(task.getProjectId());
        entity.setTaskId(task.getId());
        entity.setTaskRunId(run.getId());
        entity.setTaskStepId(step.getId());
        entity.setPhase(phase == null ? "UNKNOWN" : phase.name());
        entity.setRunOutcome(outcome.getOutcome().name());
        entity.setStepRole(safeRole(step.getRole()));
        entity.setExecutionMode(TaskStepExecutionMode.resolve(step.getExecutionMode(), step.getRole()).name());
        entity.setSource(safeSource(outcome.getDiagnosticSource()));
        entity.setFailureCode(rawCode);
        entity.setPublicFailureCode(publicCode);
        entity.setExceptionType(safeExceptionType(outcome.getDiagnosticExceptionType()));
        entity.setFailureDetail(detail);
        entity.setDiagnosticContext(diagnosticContext(outcome));
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

    private boolean isFailed(AgentRunOutcome outcome) {
        return outcome != null && outcome.getOutcome() != null && switch (outcome.getOutcome()) {
            case FAILED, FAILED_QUALITY, FAILED_INFRASTRUCTURE -> true;
            default -> false;
        };
    }

    private String publicFailureCode(AgentRunOutcome outcome, String rawCode) {
        if (outcome.getOutcome() == RunOutcome.FAILED_INFRASTRUCTURE) {
            return ExecutionContentSanitizer.stableInfrastructureCode(outcome.getFailureCode());
        }
        String publicCode = ExecutionContentSanitizer.publicFailureCode(rawCode);
        return publicCode == null ? "EXECUTION_FAILED" : publicCode;
    }

    private String derivedFailureCode(AgentRunOutcome outcome) {
        if (outcome.getTestResult() != null && !outcome.getTestResult().isSuccess()
                && outcome.getTestResult().getExitCode() != 0) {
            return "PROCESS_EXIT_NONZERO";
        }
        return "UNCLASSIFIED_FAILURE";
    }

    private Map<String, Object> diagnosticContext(AgentRunOutcome outcome) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("message", safeText(outcome.getMessage(), 1_000));
        if (outcome.getTestResult() != null) {
            context.put("test", testContext(outcome.getTestResult()));
        }
        if (outcome.getReviewResult() != null) {
            context.put("review", reviewContext(outcome.getReviewResult()));
        }
        return context;
    }

    private Map<String, Object> testContext(TestResult test) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("verificationMode", safeText(test.getVerificationMode(), 64));
        value.put("exitCode", test.getExitCode());
        value.put("needsCodingFix", test.isNeedsCodingFix());
        List<TestResult.Failure> failures = test.getFailures() == null ? List.of() : test.getFailures();
        value.put("failureCount", failures.size());
        value.put("failures", failures.stream().filter(java.util.Objects::nonNull).limit(MAX_CONTEXT_ITEMS).map(failure -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", safeText(failure.getName(), 160));
            item.put("reason", safeText(failure.getReason(), MAX_CONTEXT_TEXT_LENGTH));
            item.put("severity", safeText(failure.getSeverity(), 32));
            return item;
        }).toList());
        return value;
    }

    private Map<String, Object> reviewContext(ReviewResult review) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("needsCodingFix", review.isNeedsCodingFix());
        value.put("findingCount", review.getFindings() == null ? 0 : review.getFindings().size());
        value.put("findings", (review.getFindings() == null ? List.<ReviewResult.Finding>of() : review.getFindings())
                .stream().filter(java.util.Objects::nonNull).limit(MAX_CONTEXT_ITEMS).map(finding -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("severity", safeText(finding.getSeverity(), 32));
                    item.put("file", safeText(finding.getFile(), 160));
                    item.put("line", finding.getLine());
                    item.put("issue", safeText(finding.getIssue(), MAX_CONTEXT_TEXT_LENGTH));
                    item.put("suggestion", safeText(finding.getSuggestion(), MAX_CONTEXT_TEXT_LENGTH));
                    return item;
                }).toList());
        return value;
    }

    private String safeText(String value, int maxLength) {
        String sanitized = ExecutionContentSanitizer.sanitizeDiagnosticDetail(value == null ? "" : value).strip();
        if (sanitized.isEmpty()) {
            return null;
        }
        return sanitized.length() <= maxLength ? sanitized : sanitized.substring(0, maxLength) + "…";
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
            return "UNCLASSIFIED_FAILURE";
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z][A-Z0-9_]{0,63}") ? normalized : "UNCLASSIFIED_FAILURE";
    }

    private String safeRole(String value) {
        if (value == null || value.isBlank()) {
            return "UNKNOWN";
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        return normalized.matches("[A-Z][A-Z0-9_]{0,31}") ? normalized : "UNKNOWN";
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
