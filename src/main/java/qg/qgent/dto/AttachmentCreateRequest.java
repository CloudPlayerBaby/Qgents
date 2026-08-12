package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建附件直传凭证请求（契约 §7）。
 */
@Data
public class AttachmentCreateRequest {

    /** 原始文件名（必填，≤512）。 */
    @NotBlank
    @Size(max = 512)
    @Schema(description = "原始文件名（必填）", maxLength = 512, requiredMode = Schema.RequiredMode.REQUIRED)
    private String fileName;

    /** MIME 媒体类型（≤255，可空）。 */
    @Size(max = 255)
    @Schema(description = "MIME 媒体类型", maxLength = 255)
    private String contentType;

    /** 文件大小（字节，可空）。 */
    @Schema(description = "文件大小（字节）")
    private Long sizeBytes;
}
