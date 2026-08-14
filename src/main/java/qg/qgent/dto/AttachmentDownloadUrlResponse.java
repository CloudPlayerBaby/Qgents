package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 附件下载地址响应：服务端签发短期有效的预签名 GET 地址，客户端据此下载/预览文件。
 * <p>
 * 地址短时有效、不含长期凭据；过期需重新签发。返回前已按项目成员与附件归属校验。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentDownloadUrlResponse {

    /** 附件 ID（UUIDv7，字符串形式）。 */
    @Schema(description = "附件 ID")
    private String attachmentId;

    /** 预签名下载地址（GET）。 */
    @Schema(description = "预签名下载地址")
    private String downloadUrl;

    /** 地址过期时间（UTC）。 */
    @Schema(description = "地址过期时间")
    private LocalDateTime expiresAt;
}
