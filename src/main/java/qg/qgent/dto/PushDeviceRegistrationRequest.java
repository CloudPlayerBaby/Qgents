package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 注册或刷新当前登录用户的移动端推送设备。 */
@Data
public class PushDeviceRegistrationRequest {
    @NotBlank
    @Size(max = 128)
    @Schema(description = "App 安装实例稳定 ID；重装后生成新值", maxLength = 128)
    private String installationId;

    @NotBlank
    @Pattern(regexp = "ANDROID|IOS")
    @Schema(description = "客户端平台", allowableValues = {"ANDROID", "IOS"})
    private String platform;

    @NotBlank
    @Size(max = 4096)
    @Schema(description = "FCM registration token；仅写入，服务端加密保存且不回显", maxLength = 4096)
    private String token;
}
