package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建头像直传凭证请求：客户端仅声明图片类型与大小，对象键由服务端生成（不使用客户端文件名）。
 */
@Data
public class AvatarCredentialRequest {

    /**
     * MIME 媒体类型，必须为 image/* 开头（必填）。
     */
    @NotBlank
    @Size(max = 255)
    @Schema(description = "MIME 媒体类型（image/*）", maxLength = 255, requiredMode = Schema.RequiredMode.REQUIRED)
    private String mediaType;

    /**
     * 文件大小（字节，必填；上限 5MB）。
     */
    @NotNull
    @Schema(description = "文件大小（字节，必填；上限 5MB）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long sizeBytes;
}
