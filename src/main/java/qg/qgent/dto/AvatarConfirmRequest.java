package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 确认头像上传的请求（契约：头像直传 OSS 后调用）。
 * <p>
 * objectKey 为 {@code POST /me/avatar/credential} 直传凭证签发时返回给客户端的对象键，
 * 客户端把它原样带回确认；服务端据此校验对象已真实存在并生成公共读 URL。
 */
@Data
public class AvatarConfirmRequest {

    /**
     * 头像对象键（avatars/{userId}/{uuid}.{ext}），服务端签发，客户端仅原样回传。
     */
    @NotBlank
    @Size(max = 512)
    @Schema(description = "头像对象键（直传凭证签发），客户端上传后原样回传", maxLength = 512,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String objectKey;
}
