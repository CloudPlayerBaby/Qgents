package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

/** 不含设备 Token 的推送设备视图。 */
@Data
@AllArgsConstructor
public class PushDeviceResponse {
    @Schema(description = "设备注册 ID")
    private String id;
    @Schema(description = "App 安装实例 ID")
    private String installationId;
    @Schema(description = "ANDROID 或 IOS")
    private String platform;
    @Schema(description = "是否启用推送")
    private boolean active;
}
