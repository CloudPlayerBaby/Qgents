package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 附件直传凭证响应（契约 §7）：客户端用 uploadUrl + method + headers 直接上传文件到对象存储。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentUploadCredentialResponse {

    /** 附件 ID。 */
    @Schema(description = "附件 ID")
    private String attachmentId;

    /** 上传地址（对象存储预签名地址或本地签名端点）。 */
    @Schema(description = "上传地址")
    private String uploadUrl;

    /** 上传方法，如 PUT。 */
    @Schema(description = "上传方法", example = "PUT")
    private String method;

    /** 凭证过期时间（UTC）。 */
    @Schema(description = "凭证过期时间（UTC）")
    private LocalDateTime expiresAt;

    /** 上传时必须携带的请求头（如 Content-Type）。 */
    @Schema(description = "上传请求头")
    private Map<String, String> headers;
}
