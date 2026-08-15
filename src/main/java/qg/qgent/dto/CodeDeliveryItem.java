package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * CODE 交付项：Task 级 DiffReviewBatch 聚合（契约 v1.8.0 §20）。
 * <p>
 * 只读消费 DiffReviewBatch / Diff / MR 数据；写操作仍走 Task 级 diff-review 正式接口。
 * 无数据的集合字段返回空数组，不返回 null；真实不存在的关联返回 null。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class CodeDeliveryItem extends DeliveryItem {

    /**
     * 涉及的仓库摘要列表；无数据时返回空数组。
     */
    @Schema(description = "涉及的仓库摘要列表")
    private List<RepositoryRef> repositories;

    /**
     * 总 Diff 批次 ID（与 resourceId 一致，显式表达语义）。
     */
    @Schema(description = "总 Diff 批次 ID")
    private String diffReviewId;

    /**
     * 批次审核状态：PENDING_CONFIRMATION / ACCEPTED / REJECTED。
     */
    @Schema(description = "批次审核状态")
    private String reviewStatus;

    /**
     * 总体交付状态：NOT_STARTED / DELIVERING / DELIVERED / PARTIALLY_DELIVERED / FAILED。
     */
    @Schema(description = "总体交付状态")
    private String deliveryStatus;

    /**
     * 变更文件数。
     */
    @Schema(description = "变更文件数")
    private int filesChanged;

    /**
     * 新增行数。
     */
    @Schema(description = "新增行数")
    private int additions;

    /**
     * 删除行数。
     */
    @Schema(description = "删除行数")
    private int deletions;

    /**
     * 逐仓库交付摘要；无数据时返回空数组。
     */
    @Schema(description = "逐仓库交付摘要")
    private List<RepositoryDeliverySummary> repositoryDeliveries;

    /**
     * 首个已创建 MR 摘要（聚合页入口）；无 MR 时 null。
     */
    @Schema(description = "首个 MR 摘要")
    private MergeRequestSummary mergeRequest;

    /**
     * 仓库摘要引用。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RepositoryRef {
        @Schema(description = "项目仓库绑定 ID")
        private String repositoryId;
        @Schema(description = "仓库名称")
        private String name;
        @Schema(description = "源分支")
        private String branch;
    }
}
