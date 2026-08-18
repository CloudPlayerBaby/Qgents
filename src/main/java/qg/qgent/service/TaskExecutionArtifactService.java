package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.TaskExecutionArtifactResponse;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskExecutionArtifactEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.mapper.TaskExecutionArtifactMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.orchestration.ExecutionContentSanitizer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Persists user-visible, non-delivery timeline summaries for a Task.
 */
@Service
public class TaskExecutionArtifactService {
    private static final int MAX_SUMMARY_DEPTH = 4;
    private static final int MAX_SUMMARY_ENTRIES = 32;
    private static final int MAX_TEXT_LENGTH = 1_000;
    private final TaskExecutionArtifactMapper artifacts;
    private final TaskMapper tasks;
    private final ProjectAccessService access;
    private final EventService events;

    public TaskExecutionArtifactService(TaskExecutionArtifactMapper artifacts, TaskMapper tasks,
                                        ProjectAccessService access, EventService events) {
        this.artifacts = artifacts;
        this.tasks = tasks;
        this.access = access;
        this.events = events;
    }

    @Transactional
    public TaskExecutionArtifactEntity createPlan(TaskEntity task, Map<String, Object> summary) {
        return create(task, null, null, "PLAN", summary);
    }

    @Transactional
    public TaskExecutionArtifactEntity createRunArtifact(TaskEntity task, TaskRunEntity run, TaskStepEntity step,
                                                         String type, Map<String, Object> summary) {
        if (run == null || step == null || !task.getId().equals(run.getTaskId()) || !task.getId().equals(step.getTaskId())
                || !step.getId().equals(run.getTaskStepId())) {
            throw new IllegalArgumentException("Task artifact ownership is inconsistent");
        }
        return create(task, run, step, type, summary);
    }

    public List<TaskExecutionArtifactResponse> list(UUID projectId, UUID taskId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        TaskEntity task = requireTask(projectId, taskId);
        return artifacts.selectList(Wrappers.<TaskExecutionArtifactEntity>lambdaQuery()
                        .eq(TaskExecutionArtifactEntity::getTaskId, task.getId())
                        .orderByAsc(TaskExecutionArtifactEntity::getSequenceNo))
                .stream().map(this::response).toList();
    }

    private TaskExecutionArtifactEntity create(TaskEntity task, TaskRunEntity run, TaskStepEntity step, String type,
                                               Map<String, Object> summary) {
        TaskEntity locked = tasks.selectByIdForUpdate(task.getId());
        if (locked == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task does not exist");
        }
        int next = artifacts.selectList(Wrappers.<TaskExecutionArtifactEntity>lambdaQuery()
                        .eq(TaskExecutionArtifactEntity::getTaskId, task.getId())
                        .orderByDesc(TaskExecutionArtifactEntity::getSequenceNo).last("LIMIT 1"))
                .stream().findFirst().map(value -> value.getSequenceNo() + 1).orElse(1);
        TaskExecutionArtifactEntity artifact = new TaskExecutionArtifactEntity();
        artifact.setId(UuidV7.next());
        artifact.setTaskId(task.getId());
        artifact.setTaskRunId(run == null ? null : run.getId());
        artifact.setTaskStepId(step == null ? null : step.getId());
        artifact.setSequenceNo(next);
        artifact.setArtifactType(type);
        artifact.setSummary(sanitizeSummary(summary));
        artifact.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        artifacts.insert(artifact);
        events.publish(task.getProjectId(), task.getRequirementGroupId(), run == null ? "task.artifact.created"
                : "task-run.artifact.created", artifact.getId().toString(), payload(artifact));
        return artifact;
    }

    private Map<String, Object> payload(TaskExecutionArtifactEntity artifact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("taskId", artifact.getTaskId());
        value.put("artifactId", artifact.getId());
        value.put("sequenceNo", artifact.getSequenceNo());
        value.put("artifactType", artifact.getArtifactType());
        if (artifact.getTaskRunId() != null) value.put("taskRunId", artifact.getTaskRunId());
        if (artifact.getTaskStepId() != null) value.put("taskStepId", artifact.getTaskStepId());
        return value;
    }

    /**
     * 用户可见执行卡片仅保留有限的结构化摘要，阻断敏感字段、绝对宿主机路径和原始命令文本。
     */
    Map<String, Object> sanitizeSummary(Map<String, Object> summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (summary == null) {
            return result;
        }
        boolean infrastructureFailure = "FAILED_INFRASTRUCTURE".equals(String.valueOf(summary.get("outcome")));
        for (Map.Entry<String, Object> entry : summary.entrySet()) {
            if (result.size() == MAX_SUMMARY_ENTRIES || sensitiveKey(entry.getKey())) {
                continue;
            }
            Object value = sanitizeValue(entry.getValue(), 0);
            if (value != null) {
                result.put(entry.getKey(), value);
            }
        }
        if (infrastructureFailure) {
            String code = ExecutionContentSanitizer.stableInfrastructureCode(
                    summary.get("failureCode") == null ? null : String.valueOf(summary.get("failureCode")));
            result.put("failureCode", code);
            result.put("message", ExecutionContentSanitizer.infrastructureDescription(code));
        }
        return result;
    }

    private Object sanitizeValue(Object value, int depth) {
        if (value == null || depth > MAX_SUMMARY_DEPTH) {
            return null;
        }
        if (value instanceof String text) {
            return sanitizeText(text);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> source) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (result.size() == MAX_SUMMARY_ENTRIES || !(entry.getKey() instanceof String key) || sensitiveKey(key)) {
                    continue;
                }
                Object sanitized = sanitizeValue(entry.getValue(), depth + 1);
                if (sanitized != null) {
                    result.put(key, sanitized);
                }
            }
            return result;
        }
        if (value instanceof Collection<?> source) {
            return source.stream().limit(MAX_SUMMARY_ENTRIES).map(item -> sanitizeValue(item, depth + 1))
                    .filter(item -> item != null).toList();
        }
        return null;
    }

    private String sanitizeText(String value) {
        String text = ExecutionContentSanitizer.sanitize(value).strip();
        return text.length() <= MAX_TEXT_LENGTH ? text : text.substring(0, MAX_TEXT_LENGTH) + "...";
    }

    private boolean sensitiveKey(String key) {
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("token") || normalized.contains("password") || normalized.contains("secret")
                || normalized.contains("privatekey") || normalized.contains("api-key")
                || normalized.contains("api_key") || normalized.contains("authorization")
                || normalized.equals("command") || normalized.equals("stdout")
                || normalized.equals("stderr");
    }

    private TaskEntity requireTask(UUID projectId, UUID taskId) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task does not exist or is not visible");
        }
        return task;
    }

    private TaskExecutionArtifactResponse response(TaskExecutionArtifactEntity value) {
        Map<String, Object> summary = value.getSummary() == null ? Map.of() : value.getSummary();
        return new TaskExecutionArtifactResponse(value.getId().toString(), value.getTaskId().toString(),
                value.getTaskRunId() == null ? null : value.getTaskRunId().toString(),
                value.getTaskStepId() == null ? null : value.getTaskStepId().toString(), value.getSequenceNo(),
                value.getArtifactType(), displayTitle(value.getArtifactType()), displayStatus(summary),
                displayDescription(summary), summary, List.of(),
                value.getCreatedAt().toInstant(ZoneOffset.UTC).toString());
    }

    /**
     * 由产物类型派生稳定展示标题，不虚构业务标题。
     */
    private String displayTitle(String artifactType) {
        if (artifactType == null) {
            return null;
        }
        return switch (artifactType) {
            case "PLAN" -> "计划";
            case "CODING" -> "代码编写";
            case "TESTING" -> "测试";
            case "REVIEWING" -> "代码审查";
            default -> artifactType;
        };
    }

    /**
     * 由执行结果 outcome 派生状态：SUCCEEDED → SUCCEEDED，其余终态 → FAILED，无 outcome 时 null。
     */
    private String displayStatus(Map<String, Object> summary) {
        Object outcome = summary.get("outcome");
        if (outcome == null) {
            return null;
        }
        return "SUCCEEDED".equals(outcome.toString()) ? "SUCCEEDED" : "FAILED";
    }

    /**
     * 提取产物摘要中的脱敏说明（优先 coding 的 summary，其次 plan 的任务理解），截断到 200 字符。
     */
    private String displayDescription(Map<String, Object> summary) {
        Object value = summary.get("summary");
        if (!(value instanceof String s) || s.isBlank()) {
            value = summary.get("taskUnderstanding");
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        return stripped.length() <= 200 ? stripped : stripped.substring(0, 200) + "...";
    }
}
