package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 交付中心聚合统计（契约 v1.8.0 §20，成员 B B02）。
 * <p>
 * 统计针对完整筛选数据集计算，不由当前分页推导；
 * countsByType 恒含 CODE/MEMORY/SKILL 三 key（值为 0 也返回）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeliverySummaryResponse {

    /**
     * 筛选数据集总数。
     */
    @Schema(description = "筛选数据集总数")
    private long total;

    /**
     * 按资源类型计数（恒含 CODE/MEMORY/SKILL 三 key）。
     */
    @Schema(description = "按资源类型计数")
    private TypeCounts countsByType;

    /**
     * 按展示状态计数。
     */
    @Schema(description = "按展示状态计数")
    private StatusCounts countsByStatus;

    /**
     * 当前用户待处理数量（按 capabilities 派生）。
     */
    @Schema(description = "当前用户待处理数量")
    private long pendingForCurrentUser;

    /**
     * 按仓库聚合摘要（仅 CODE 数据参与）；无数据时返回空数组。
     */
    @Schema(description = "按仓库聚合摘要")
    private List<RepositorySummaryItem> repositorySummaries;

    /**
     * 按需求群聚合摘要；无数据时返回空数组。
     */
    @Schema(description = "按需求群聚合摘要")
    private List<RequirementGroupSummaryItem> requirementGroupSummaries;

    /**
     * 统计计算时间（ISO8601 UTC）。
     */
    @Schema(description = "统计计算时间")
    private String updatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TypeCounts {
        @Schema(description = "CODE 数量")
        private long code;
        @Schema(description = "MEMORY 数量")
        private long memory;
        @Schema(description = "SKILL 数量")
        private long skill;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusCounts {
        @Schema(description = "DRAFT 数量")
        private long draft;
        @Schema(description = "PENDING_REVIEW 数量")
        private long pendingReview;
        @Schema(description = "PROCESSING 数量")
        private long processing;
        @Schema(description = "ACCEPTED 数量")
        private long accepted;
        @Schema(description = "REJECTED 数量")
        private long rejected;
        @Schema(description = "DELIVERED 数量")
        private long delivered;
        @Schema(description = "FAILED 数量")
        private long failed;
        @Schema(description = "ARCHIVED 数量")
        private long archived;
    }
}
