package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 头像直传凭证响应：客户端用它先直传 OSS（uploadUrl），再携带 objectKey 调确认接口。
 * objectKey 由服务端签发，客户端只能原样回传，不可自造。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AvatarCredentialResponse {

    /**
     * 头像对象键（avatars/{userId}/{uuid}.{ext}），确认时回传。
     */
    @Schema(description = "头像对象键，确认时原样回传")
    private String objectKey;

    /**
     * 上传地址（对象存储预签名地址）。
     */
    @Schema(description = "上传地址")
    private String uploadUrl;

    /**
     * 上传方法，固定 PUT。
     */
    @Schema(description = "上传方法", example = "PUT")
    private String method;

    /**
     * 上传请求头。
     */
    @Schema(description = "上传请求头")
    private Map<String, String> headers;

    /**
     * 凭证过期时间（UTC）。
     */
    @Schema(description = "凭证过期时间（UTC）")
    private LocalDateTime expiresAt;
}
