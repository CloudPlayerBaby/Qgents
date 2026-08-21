package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 设置 / 取消群置顶请求（个人偏好，仅影响当前用户）。
 */
@Data
public class PinGroupRequest {

    /**
     * 是否置顶：true 置顶，false 取消置顶（必填）。
     */
    @NotNull
    @Schema(description = "是否置顶：true 置顶，false 取消置顶（必填）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean pinned;
}
