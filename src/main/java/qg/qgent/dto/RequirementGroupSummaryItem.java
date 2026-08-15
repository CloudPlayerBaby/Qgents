package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 交付统计中的需求群聚合摘要（契约 v1.8.0 §20）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequirementGroupSummaryItem {

    /**
     * 需求群 ID。
     */
    @Schema(description = "需求群 ID")
    private String requirementGroupId;

    /**
     * 需求群名称。
     */
    @Schema(description = "需求群名称")
    private String name;

    /**
     * 关联交付总数。
     */
    @Schema(description = "关联交付总数")
    private long total;

    /**
     * 待处理数量。
     */
    @Schema(description = "待处理数量")
    private long pending;
}
