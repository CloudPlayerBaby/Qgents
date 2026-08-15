package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务中心待处理事项提示。
 * <p>
 * kind 枚举：INPUT_REQUIRED/APPROVAL_REQUIRED/BLOCKED/EXECUTION_FAILED/DIFF_CONFIRMATION_REQUIRED/DELIVERY_FAILED。
 * 只返回脱敏用户可见摘要；taskRunId/inputRequestId/diffReviewBatchId/repositoryId 供前端跳转
 * 具体处理入口，无对应关联时为 null。没有待处理事项时该对象整体为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Attention {

    /**
     * 待处理事项类型。
     */
    @Schema(description = "待处理事项类型")
    private String kind;

    /**
     * 一行标题，如“等待补充验收说明”。
     */
    @Schema(description = "一行标题")
    private String title;

    /**
     * 脱敏摘要文本，说明需要用户做什么。
     */
    @Schema(description = "脱敏摘要")
    private String summary;

    /**
     * 关联的任务运行 ID，可为 null。
     */
    @Schema(description = "关联任务运行 ID")
    private String taskRunId;

    /**
     * 关联的输入/审批请求 ID，可为 null。
     */
    @Schema(description = "关联输入/审批请求 ID")
    private String inputRequestId;

    /**
     * 关联的总 Diff 批次 ID（DIFF_CONFIRMATION_REQUIRED/DELIVERY_FAILED 时非 null）。
     */
    @Schema(description = "关联总 Diff 批次 ID")
    private String diffReviewBatchId;

    /**
     * 关联的项目仓库绑定 ID（DELIVERY_FAILED 时为首个失败仓库）。
     */
    @Schema(description = "关联项目仓库绑定 ID")
    private String repositoryId;

    /**
     * 待处理事项发生时间（UTC）。
     */
    @Schema(description = "待处理事项发生时间")
    private String createdAt;
}
