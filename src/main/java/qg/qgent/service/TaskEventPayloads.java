package qg.qgent.service;

import qg.qgent.entity.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Task 模型项目级事件载荷构造器（SSE 契约）。
 * 所有 payload 均为已脱敏内容；六种事件类型及关联 ID 约定：
 * <ul>
 *   <li>task.updated      → {projectId, taskId, requirementGroupId, status, workspaceId, timestamp}</li>
 *   <li>task-step.updated → {projectId, taskId, taskStepId, sequenceNo, status, timestamp}</li>
 *   <li>task-run.updated  → {projectId, taskId, taskStepId, taskRunId, status, sequence, timestamp}</li>
 *   <li>input-required    → {projectId, taskId, taskStepId, taskRunId, inputRequestId, kind, status, prompt, timestamp}</li>
 *   <li>approval-required → {projectId, taskId, taskStepId, taskRunId, inputRequestId, kind, status, prompt, timestamp}</li>
 *   <li>diff.created      → {projectId, taskId, diffId, repositoryId, sourceBranch, headCommit, status, timestamp}</li>
 * </ul>
 * headCommit 在 Diff 尚未产生真实提交前可为空，payload 中省略该键。
 */
public final class TaskEventPayloads {
    private TaskEventPayloads() {
    }

    public static Map<String, Object> taskUpdated(TaskEntity task) {
        Map<String, Object> p = new HashMap<>();
        p.put("projectId", task.getProjectId());
        p.put("taskId", task.getId());
        p.put("requirementGroupId", task.getRequirementGroupId());
        p.put("status", task.getStatus());
        p.put("deliveryMode", task.getDeliveryMode());
        p.put("workspaceId", task.getWorkspaceId());
        p.put("timestamp", Instant.now().toString());
        return p;
    }

    public static Map<String, Object> taskStepUpdated(UUID projectId, TaskStepEntity step) {
        Map<String, Object> p = new HashMap<>();
        p.put("projectId", projectId);
        p.put("taskId", step.getTaskId());
        p.put("taskStepId", step.getId());
        p.put("sequenceNo", step.getSequenceNo());
        p.put("status", step.getStatus());
        p.put("timestamp", Instant.now().toString());
        return p;
    }

    public static Map<String, Object> taskRunUpdated(TaskRunEntity run, long sequence) {
        Map<String, Object> p = new HashMap<>();
        p.put("projectId", run.getProjectId());
        p.put("taskId", run.getTaskId());
        p.put("taskStepId", run.getTaskStepId());
        p.put("taskRunId", run.getId());
        p.put("status", run.getStatus());
        p.put("sequence", sequence);
        p.put("timestamp", Instant.now().toString());
        return p;
    }

    public static Map<String, Object> inputRequest(UUID projectId, UUID taskId, UUID taskStepId, UUID taskRunId,
                                                   InputRequestEntity req) {
        Map<String, Object> p = new HashMap<>();
        p.put("projectId", projectId);
        p.put("taskId", taskId);
        p.put("taskStepId", taskStepId);
        p.put("taskRunId", taskRunId);
        p.put("inputRequestId", req.getId());
        p.put("kind", req.getKind());
        p.put("status", req.getStatus());
        p.put("prompt", req.getPrompt());
        p.put("timestamp", Instant.now().toString());
        return p;
    }

    public static Map<String, Object> diffCreated(DiffEntity diff) {
        Map<String, Object> p = new HashMap<>();
        p.put("projectId", diff.getProjectId());
        p.put("taskId", diff.getTaskId());
        p.put("diffId", diff.getId());
        p.put("repositoryId", diff.getProjectRepositoryId());
        p.put("sourceBranch", diff.getSourceBranch());
        if (diff.getHeadCommit() != null) {
            p.put("headCommit", diff.getHeadCommit());
        }
        p.put("status", diff.getStatus());
        p.put("timestamp", Instant.now().toString());
        return p;
    }
}
