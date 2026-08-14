package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务级多仓库总 Diff 审核与交付摘要。
 * <p>
 * diffs 为批次内各仓库 Diff 摘要；repositoryDeliveries 补充逐仓库交付状态、失败原因与已创建 MR 摘要，
 * 供刷新后恢复部分成功详情。deliveryStatus 为 PARTIALLY_DELIVERED 时通过 repositoryDeliveries 指出成功与失败仓库。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffReviewBatchResponse {

    /** 总 Diff 批次 ID（UUIDv7，字符串形式）。 */
    @Schema(description = "总 Diff 批次 ID")
    private String id;

    /** 所属任务 ID。 */
    @Schema(description = "所属任务 ID")
    private String taskId;

    /** 审核状态：PENDING_CONFIRMATION/ACCEPTED/REJECTED。 */
    @Schema(description = "审核状态")
    private String reviewStatus;

    /** 交付状态：NOT_STARTED/DELIVERING/PARTIALLY_DELIVERED/DELIVERED/FAILED。 */
    @Schema(description = "交付状态")
    private String deliveryStatus;

    /** 聚合 Hash。 */
    @Schema(description = "聚合 Hash")
    private String aggregateHash;

    /** 审核拒绝原因，可为 null。 */
    @Schema(description = "审核拒绝原因")
    private String reviewReason;

    /** 批次内各仓库 Diff 摘要。 */
    @Schema(description = "批次内 Diff 摘要")
    private List<DiffListItemResponse> diffs;

    /** 逐仓库交付详情（成功/失败/MR 摘要）。 */
    @Schema(description = "逐仓库交付详情")
    private List<RepositoryDelivery> repositoryDeliveries;
}
