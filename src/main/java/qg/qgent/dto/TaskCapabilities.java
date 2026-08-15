package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 当前用户针对当前任务的操作能力，由后端按任务状态与调用者身份派生，前端不猜测。
 * <p>
 * 每个能力可携带 xxxDisabledReason（不可操作时的稳定错误码，可操作时为 null），供前端展示禁用原因。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskCapabilities {

    /**
     * 当前用户是否可取消任务。
     */
    @Schema(description = "是否可取消任务")
    private boolean canCancel;

    /**
     * 不可取消的原因码，如 TASK_NOT_CANCELLABLE。
     */
    @Schema(description = "不可取消原因码")
    private String cancelDisabledReason;

    /**
     * 当前用户是否可替换待执行步骤的 Agent。
     */
    @Schema(description = "是否可替换待执行步骤 Agent")
    private boolean canReplacePendingStepAgent;

    /**
     * 不可替换 Agent 的原因码。
     */
    @Schema(description = "不可替换 Agent 原因码")
    private String replacePendingStepAgentDisabledReason;

    /**
     * 当前用户是否可确认总 Diff。
     */
    @Schema(description = "是否可确认总 Diff")
    private boolean canConfirmDiffReview;

    /**
     * 不可确认总 Diff 的原因码。
     */
    @Schema(description = "不可确认总 Diff 原因码")
    private String confirmDiffReviewDisabledReason;

    /**
     * 当前用户是否可拒绝总 Diff。
     */
    @Schema(description = "是否可拒绝总 Diff")
    private boolean canRejectDiffReview;

    /**
     * 不可拒绝总 Diff 的原因码。
     */
    @Schema(description = "不可拒绝总 Diff 原因码")
    private String rejectDiffReviewDisabledReason;

    /**
     * 当前用户是否可重试交付。
     */
    @Schema(description = "是否可重试交付")
    private boolean canRetryDelivery;

    /**
     * 不可重试交付的原因码。
     */
    @Schema(description = "不可重试交付原因码")
    private String retryDeliveryDisabledReason;
}
