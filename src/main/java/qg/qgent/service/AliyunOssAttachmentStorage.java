package qg.qgent.service;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import qg.qgent.config.AliyunOssProperties;
import qg.qgent.dto.UploadCredential;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * 阿里云 OSS 预签名直传凭证（附件真实存储策略，@Primary）。
 * <p>
 * 仅在 aliyun.oss.enabled=true 时生效：上传签发预签名 PUT URL（客户端直接 PUT 文件字节到 OSS 桶），
 * 下载签发预签名 GET URL，确认上传通过 doesObjectExist 校验。AccessKey 由环境变量注入
 * （见 {@link AliyunOssProperties}），本类不接触密钥明文。
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "aliyun.oss", name = "enabled", havingValue = "true")
public class AliyunOssAttachmentStorage implements AttachmentStorageStrategy {

    private final OSS oss;
    private final AliyunOssProperties properties;

    public AliyunOssAttachmentStorage(OSS oss, AliyunOssProperties properties) {
        this.oss = oss;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "ALIYUN_OSS_PRESIGNED";
    }

    @Override
    public UploadCredential createCredential(UUID attachmentId, String objectKey, String fileName, String mediaType,
                                             Long sizeBytes) {
        Date expiresAt = new Date(System.currentTimeMillis() + properties.getPresignExpirySeconds() * 1000L);
        URL url = oss.generatePresignedUrl(properties.getBucketName(), objectKey, expiresAt, HttpMethod.PUT);
        LocalDateTime expiry = LocalDateTime.ofInstant(expiresAt.toInstant(), ZoneOffset.UTC);
        // 预签名 PUT 不要求固定请求头，避免 Content-Type 参与签名导致前端上传校验失败
        return new UploadCredential(url.toString(), "PUT", Map.of(), expiry);
    }

    @Override
    public String createDownloadUrl(String objectKey, long expiresSeconds) {
        Date expiresAt = new Date(System.currentTimeMillis() + expiresSeconds * 1000L);
        URL url = oss.generatePresignedUrl(properties.getBucketName(), objectKey, expiresAt, HttpMethod.GET);
        return url.toString();
    }

    @Override
    public boolean objectExists(String objectKey) {
        return oss.doesObjectExist(properties.getBucketName(), objectKey);
    }

    @Override
    public AttachmentContent loadContent(String objectKey, String fileName, String contentType) {
        OSSObject object = oss.getObject(properties.getBucketName(), objectKey);
        String resolvedType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        Long size = object.getObjectMetadata() == null ? null : object.getObjectMetadata().getContentLength();
        return new AttachmentContent(object.getObjectContent(), resolvedType, fileName, size);
    }
}
