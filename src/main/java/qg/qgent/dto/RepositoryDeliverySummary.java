package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单仓库交付摘要（契约 v1.8.0 §20，CODE 交付项）。
 * <p>
 * deliveryStatus 枚举：NOT_STARTED / COMMITTED / MR_CREATED / FAILED；
 * failureCode / failureReason 仅失败时非 null；mergeRequest 仅 MR_CREATED 且存在真实 MR 时非 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RepositoryDeliverySummary {

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
     * 交付状态：NOT_STARTED / COMMITTED / MR_CREATED / FAILED。
     */
    @Schema(description = "交付状态")
    private String deliveryStatus;

    /**
     * 失败错误码；无失败时 null。
     */
    @Schema(description = "失败错误码")
    private String failureCode;

    /**
     * 脱敏失败原因；无失败时 null。
     */
    @Schema(description = "脱敏失败原因")
    private String failureReason;

    /**
     * 真实 MR 摘要；无 MR 时 null。
     */
    @Schema(description = "MR 摘要")
    private MergeRequestSummary mergeRequest;

    /**
     * 更新时间（ISO8601 UTC）。
     */
    @Schema(description = "更新时间")
    private String updatedAt;
}
