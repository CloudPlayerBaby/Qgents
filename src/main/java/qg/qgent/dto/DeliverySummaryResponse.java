package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 交付中心聚合统计（契约 v1.8.0 §20，成员 B B02）。
 * <p>
 * 统计针对完整筛选数据集计算，不由当前分页推导；
 * countsByType 恒含 CODE/MEMORY/SKILL 三 key（值为 0 也返回）；
 * countsByStatus 恒含 8 个正式大写枚举 key（DRAFT/PENDING_REVIEW/PROCESSING/ACCEPTED/
 * REJECTED/DELIVERED/FAILED/ARCHIVED），统计 key 统一遵循正式枚举，不使用小写或 camelCase。
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
     * 按资源类型计数（恒含 CODE/MEMORY/SKILL 三 key，正式大写枚举）。
     */
    @Schema(description = "按资源类型计数（正式大写枚举）")
    private Map<String, Long> countsByType;

    /**
     * 按展示状态计数（恒含 8 个正式大写枚举 key）。
     */
    @Schema(description = "按展示状态计数（正式大写枚举）")
    private Map<String, Long> countsByStatus;

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
}
