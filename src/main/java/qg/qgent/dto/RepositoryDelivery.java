package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 总 Diff 批次中单个仓库的交付详情（repositoryDeliveries 元素）。
 * <p>
 * deliveryStatus 枚举：NOT_STARTED/COMMITTED/PUSHED/MR_CREATED/FAILED；
 * failureCode/failureReason 仅在失败时返回脱敏原因，成功时为 null；
 * mergeRequest 在 MR_CREATED 时返回真实 MR 摘要，其余为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RepositoryDelivery {

    /**
     * 项目仓库绑定 ID。
     */
    @Schema(description = "项目仓库绑定 ID")
    private String repositoryId;

    /**
     * 仓库显示名称。
     */
    @Schema(description = "仓库显示名称")
    private String repositoryName;

    /**
     * 该仓库的总 Diff ID。
     */
    @Schema(description = "Diff ID")
    private String diffId;

    /**
     * 该仓库交付状态。
     */
    @Schema(description = "交付状态")
    private String deliveryStatus;

    /**
     * 失败原因码，可为 null。
     */
    @Schema(description = "失败原因码")
    private String failureCode;

    /**
     * 脱敏失败原因，可为 null。
     */
    @Schema(description = "脱敏失败原因")
    private String failureReason;

    /**
     * 已创建 MR 的摘要，可为 null。
     */
    @Schema(description = "MR 摘要")
    private MergeRequestBrief mergeRequest;

    /**
     * 交付状态更新时间（UTC）。
     */
    @Schema(description = "交付状态更新时间")
    private String updatedAt;
}
