package qg.qgent.service;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import qg.qgent.config.AliyunOssProperties;
import qg.qgent.dto.UploadCredential;

import java.io.InputStream;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * 阿里云 OSS 附件存储策略（真实存储，@Primary）。
 * <p>
 * 仅在 aliyun.oss.enabled=true 时生效：上传走后端代理——凭证 {@code uploadUrl} 指向代理端点，
 * 客户端把文件字节 PUT 到后端，由 {@link #storeBytes} 写入 OSS 桶（浏览器不直传 OSS，规避跨域预检）；
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
        LocalDateTime expiry = LocalDateTime.now(ZoneOffset.UTC).plusSeconds(properties.getPresignExpirySeconds());
        // 代理上传：uploadUrl 指向后端代理端点（相对路径，前端按 API base origin 解析），
        // 字节经 storeBytes 写入 OSS。Content-Type 透传给前端便于回传（代理端点不强依赖该头）。
        Map<String, String> headers = mediaType == null || mediaType.isBlank()
                ? Map.of()
                : Map.of("Content-Type", mediaType);
        return new UploadCredential("/api/v1/" + objectKey, "PUT", headers, expiry);
    }

    @Override
    public void storeBytes(String objectKey, InputStream bytes, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        if (contentType != null && !contentType.isBlank()) {
            metadata.setContentType(contentType);
        }
        oss.putObject(properties.getBucketName(), objectKey, bytes, metadata);
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
