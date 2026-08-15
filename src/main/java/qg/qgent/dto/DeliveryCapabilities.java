package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 交付项操作能力（契约 v1.8.0 §20）。
 * <p>
 * 由后端按正式资源接口的状态规则 + 当前用户角色统一派生，前端不根据角色或状态自行猜测；
 * 无能力的项返回 false + 稳定错误码（disabledReasons）。
 */
@Data
@NoArgsConstructor
public class DeliveryCapabilities {

    @Schema(description = "是否可提交审核")
    private boolean canSubmitReview;
    @Schema(description = "是否可批准/发布")
    private boolean canApprove;
    @Schema(description = "是否可拒绝")
    private boolean canReject;
    @Schema(description = "是否可归档/下线")
    private boolean canArchive;
    @Schema(description = "是否可重试交付（仅 CODE）")
    private boolean canRetryDelivery;
    @Schema(description = "是否可打开资源")
    private boolean canOpenResource;

    @Schema(description = "各项不可操作的稳定错误码；可操作时为 null")
    private DeliveryCapabilityReasons disabledReasons;

    @Data
    @NoArgsConstructor
    public static class DeliveryCapabilityReasons {
        @Schema(description = "canSubmitReview 错误码")
        private String canSubmitReview;
        @Schema(description = "canApprove 错误码")
        private String canApprove;
        @Schema(description = "canReject 错误码")
        private String canReject;
        @Schema(description = "canArchive 错误码")
        private String canArchive;
        @Schema(description = "canRetryDelivery 错误码")
        private String canRetryDelivery;
        @Schema(description = "canOpenResource 错误码")
        private String canOpenResource;
    }
}
