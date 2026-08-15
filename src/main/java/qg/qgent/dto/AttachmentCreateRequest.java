package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建附件直传凭证请求（契约 §7）。
 */
@Data
public class AttachmentCreateRequest {

    /**
     * 原始文件名（必填，≤512）。
     */
    @NotBlank
    @Size(max = 512)
    @Schema(description = "原始文件名（必填）", maxLength = 512, requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileName;

    /**
     * MIME 媒体类型（≤255，可空）。
     */
    @Size(max = 255)
    @Schema(description = "MIME 媒体类型", maxLength = 255)
    private String contentType;

    /**
     * 文件大小（字节，必填；上限由 app.attachment-max-size-bytes 配置，默认 50MB）。
     */
    @NotNull
    @Schema(description = "文件大小（字节，必填）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long sizeBytes;
}
