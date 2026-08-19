package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 交付项显式打开目标（契约 v1.8.0 §20，N04 冻结）。
 * <p>
 * 四种 kind：TASK_DIFF_REVIEW / DIFF / MEMORY / SKILL；
 * 按 kind 只填充对应 ID 字段，resourceId 不再被多义解释为 Diff 或批次。
 */
@Data
@NoArgsConstructor
public class DeliveryOpenTarget {

    /**
     * 目标类型：TASK_DIFF_REVIEW / DIFF / MEMORY / SKILL。
     */
    @Schema(description = "目标类型：TASK_DIFF_REVIEW / DIFF / MEMORY / SKILL")
    private String kind;

    /**
     * 关联任务 ID（TASK_DIFF_REVIEW / DIFF 使用）。
     */
    @Schema(description = "关联任务 ID")
    private String taskId;

    /**
     * 总 Diff 批次 ID（TASK_DIFF_REVIEW 使用）。
     */
    @Schema(description = "总 Diff 批次 ID")
    private String diffReviewBatchId;

    /**
     * Diff ID（DIFF 使用）。
     */
    @Schema(description = "Diff ID")
    private String diffId;

    /**
     * Memory ID（MEMORY 使用）。
     */
    @Schema(description = "Memory ID")
    private String memoryId;

    /**
     * Skill ID（SKILL 使用）。
     */
    @Schema(description = "Skill ID")
    private String skillId;

    /**
     * Agent ID（AGENT 使用）。
     */
    @Schema(description = "Agent ID")
    private String agentId;

    public static DeliveryOpenTarget taskDiffReview(String taskId, String diffReviewBatchId) {
        DeliveryOpenTarget target = new DeliveryOpenTarget();
        target.kind = "TASK_DIFF_REVIEW";
        target.taskId = taskId;
        target.diffReviewBatchId = diffReviewBatchId;
        return target;
    }

    public static DeliveryOpenTarget diff(String taskId, String diffId) {
        DeliveryOpenTarget target = new DeliveryOpenTarget();
        target.kind = "DIFF";
        target.taskId = taskId;
        target.diffId = diffId;
        return target;
    }

    public static DeliveryOpenTarget memory(String memoryId) {
        DeliveryOpenTarget target = new DeliveryOpenTarget();
        target.kind = "MEMORY";
        target.memoryId = memoryId;
        return target;
    }

    public static DeliveryOpenTarget skill(String skillId) {
        DeliveryOpenTarget target = new DeliveryOpenTarget();
        target.kind = "SKILL";
        target.skillId = skillId;
        return target;
    }

    public static DeliveryOpenTarget agent(String agentId) {
        DeliveryOpenTarget target = new DeliveryOpenTarget();
        target.kind = "AGENT";
        target.agentId = agentId;
        return target;
    }
}
