package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import io.swagger.v3.oas.annotations.media.Schema;

/** 总 Diff 中单个仓库的交付进展。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffRepositoryDeliveryResponse {
    @Schema(description = "项目仓库绑定 ID")
    private String repositoryId;
    @Schema(description = "仓库展示名称")
    private String repositoryName;
    @Schema(description = "Diff ID")
    private String diffId;
    @Schema(description = "仓库交付状态", allowableValues = {"NOT_STARTED", "COMMITTED", "MR_CREATED", "FAILED"})
    private String deliveryStatus;
    @Schema(description = "预留失败码；当前恒为 null")
    private String failureCode;
    @Schema(description = "脱敏失败原因")
    private String failureReason;
    @Schema(description = "真实 MR 摘要，仅 MR_CREATED 时返回")
    private DiffMergeRequestSummaryResponse mergeRequest;
    @Schema(description = "更新时间")
    private String updatedAt;
}
