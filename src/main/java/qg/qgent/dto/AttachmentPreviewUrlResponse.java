package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 附件预览地址响应（契约 §4：附件内联预览）。
 * <p>
 * 服务端按项目成员与附件归属校验后，返回不含凭证的预览地址与预览类型；浏览器拼接 ORIGIN 后通过
 * 同站 HttpOnly Cookie 访问。`downloadUrl` 为下载语义地址；当前存储策略不支持时可为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AttachmentPreviewUrlResponse {

    /**
     * 附件 ID（UUIDv7，字符串形式）。
     */
    @Schema(description = "附件 ID")
    private String attachmentId;

    /**
     * 原始文件名。
     */
    @Schema(description = "原始文件名")
    private String fileName;

    /**
     * MIME 媒体类型，可为空。
     */
    @Schema(description = "MIME 媒体类型")
    private String mediaType;

    /**
     * 文件大小（字节），可为空。
     */
    @Schema(description = "文件大小（字节）")
    private Long sizeBytes;

    /**
     * 是否可内联预览（previewType 非 UNSUPPORTED）；false 时前端应回退为下载按钮。
     */
    @Schema(description = "是否可内联预览")
    private boolean previewable;

    /**
     * 预览类型：IMAGE / PDF / TEXT / CODE / UNSUPPORTED。
     */
    @Schema(description = "预览类型：IMAGE/PDF/TEXT/CODE/UNSUPPORTED")
    private String previewType;

    /**
     * 不含凭证的预览地址（相对路径）。
     */
    @Schema(description = "Cookie 鉴权预览地址（相对路径，不含凭证）")
    private String previewUrl;

    /**
     * 下载地址（attachment 语义）；当前存储策略不支持时可为 null。
     */
    @Schema(description = "下载地址；存储策略不支持时可为 null")
    private String downloadUrl;

    /**
     * 预览地址不含临时 Token，始终为 null。
     */
    @Schema(description = "预览地址有效期；Cookie 鉴权模式固定为 null")
    private LocalDateTime expiresAt;
}
