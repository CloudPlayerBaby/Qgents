package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 交付统计中的仓库聚合摘要（契约 v1.8.0 §20）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RepositorySummaryItem {

    /**
     * 项目仓库绑定 ID。
     */
    @Schema(description = "项目仓库绑定 ID")
    private String repositoryId;

    /**
     * 仓库名称。
     */
    @Schema(description = "仓库名称")
    private String repositoryName;

    /**
     * 关联交付总数。
     */
    @Schema(description = "关联交付总数")
    private long total;

    /**
     * 已接受（ACCEPTED/DELIVERED）数量。
     */
    @Schema(description = "已接受数量")
    private long accepted;

    /**
     * 待处理（PROCESSING/PENDING_REVIEW/DRAFT）数量。
     */
    @Schema(description = "待处理数量")
    private long pending;

    /**
     * 失败（FAILED/REJECTED）数量。
     */
    @Schema(description = "失败数量")
    private long failed;

    /**
     * 交付状态（取该仓库最新交付状态）。
     */
    @Schema(description = "交付状态")
    private String deliveryStatus;

    /**
     * 可空 MR 摘要。
     */
    @Schema(description = "MR 摘要")
    private MergeRequestSummary mergeRequest;
}
