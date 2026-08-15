package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务执行统计摘要（任务中心/详情通用）。
 * <p>
 * 各计数由 TaskStep 真实状态聚合，waitingSteps 表示处于等待输入/审批的步骤（取步骤最新 TaskRun 状态），
 * blockedSteps 表示被阻塞的步骤；不返回伪造的进度百分比。currentStage 使用正式 TaskStep role，
 * currentStageTitle 为当前阶段步骤标题。requiresUserAction 与待处理事项 attention 联动。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionSummary {

    /**
     * 任务步骤总数。
     */
    @Schema(description = "步骤总数")
    private int totalSteps;

    /**
     * 待执行步骤数。
     */
    @Schema(description = "待执行步骤数")
    private int pendingSteps;

    /**
     * 执行中步骤数。
     */
    @Schema(description = "执行中步骤数")
    private int runningSteps;

    /**
     * 等待输入/审批的步骤数。
     */
    @Schema(description = "等待输入/审批的步骤数")
    private int waitingSteps;

    /**
     * 被阻塞的步骤数。
     */
    @Schema(description = "被阻塞的步骤数")
    private int blockedSteps;

    /**
     * 成功步骤数。
     */
    @Schema(description = "成功步骤数")
    private int succeededSteps;

    /**
     * 失败步骤数。
     */
    @Schema(description = "失败步骤数")
    private int failedSteps;

    /**
     * 当前阶段角色（正式 TaskStep role），无可活动阶段时为 null。
     */
    @Schema(description = "当前阶段角色")
    private String currentStage;

    /**
     * 当前阶段步骤标题，可为 null。
     */
    @Schema(description = "当前阶段标题")
    private String currentStageTitle;

    /**
     * 当前是否需要用户处理。
     */
    @Schema(description = "当前是否需要用户处理")
    private boolean requiresUserAction;
}
