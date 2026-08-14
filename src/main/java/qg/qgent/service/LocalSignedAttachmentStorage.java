package qg.qgent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import qg.qgent.dto.UploadCredential;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * 本地签名直传凭证（开发回退策略，实现 {@link AttachmentStorageStrategy}）。
 * <p>
 * 仅在未启用阿里云 OSS（aliyun.oss.enabled=false）时作为唯一策略被注入；
 * 签发指向未来上传端点的签名 URL：签名 = HMAC-SHA256(secret, attachmentId:expiresAtEpochMs)，不真实存文件。
 * 真实存储接入后由 {@link AliyunOssAttachmentStorage}（@Primary）替换。
 */
@Component
public class LocalSignedAttachmentStorage implements AttachmentStorageStrategy {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final String base;
    private final byte[] secret;
    private final Duration expiry;

    public LocalSignedAttachmentStorage(
            @Value("${app.attachment-upload-base:/api/v1/internal/attachments}") String base,
            @Value("${app.attachment-signing-secret:dev-only-change-me}") String signingSecret,
            @Value("${app.attachment-upload-expiry-seconds:900}") long expirySeconds) {
        this.base = base;
        this.secret = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.expiry = Duration.ofSeconds(expirySeconds);
    }

    @Override
    public String name() {
        return "LOCAL_SIGNED";
    }

    @Override
    public UploadCredential createCredential(UUID attachmentId, String objectKey, String fileName, String mediaType,
            Long sizeBytes) {
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plus(expiry);
        long expiresAtMillis = expiresAt.toInstant(ZoneOffset.UTC).toEpochMilli();
        String payload = attachmentId + ":" + expiresAtMillis;
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
        String uploadUrl = base + "/" + attachmentId + "/upload?expiresAt=" + expiresAtMillis
                + "&signature=" + signature;
        Map<String, String> headers = mediaType == null || mediaType.isBlank()
                ? Map.of()
                : Map.of("Content-Type", mediaType);
        return new UploadCredential(uploadUrl, "PUT", headers, expiresAt);
    }

    private byte[] hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC 初始化失败", e);
        }
    }
}
