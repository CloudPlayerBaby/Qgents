package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 总 Diff 审核与交付展示摘要（任务详情右侧 Tab 使用）。
 * <p>
 * available 为 false 时其余字段不承诺值；reviewStatus 为批次审核状态（PENDING_CONFIRMATION/ACCEPTED/REJECTED），
 * deliveryStatus 为批次交付状态（NOT_STARTED/DELIVERING/PARTIALLY_DELIVERED/DELIVERED/FAILED）。
 * filesChanged/additions/deletions 由各仓库 Diff 的变更统计聚合；尚未计算时返回 0 仅表示真实无变更数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffReviewSummary {

    /**
     * 是否存在可展示的总 Diff 批次。
     */
    @Schema(description = "是否存在总 Diff 批次")
    private boolean available;

    /**
     * 总 Diff 审核状态。
     */
    @Schema(description = "总 Diff 审核状态")
    private String reviewStatus;

    /**
     * 总 Diff 交付状态。
     */
    @Schema(description = "总 Diff 交付状态")
    private String deliveryStatus;

    /**
     * 涉及仓库数。
     */
    @Schema(description = "涉及仓库数")
    private int repositoryCount;

    /**
     * 变更文件总数。
     */
    @Schema(description = "变更文件总数")
    private int filesChanged;

    /**
     * 新增行总数。
     */
    @Schema(description = "新增行总数")
    private int additions;

    /**
     * 删除行总数。
     */
    @Schema(description = "删除行总数")
    private int deletions;
}
