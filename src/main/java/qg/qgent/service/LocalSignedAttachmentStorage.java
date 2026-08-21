package qg.qgent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import qg.qgent.dto.UploadCredential;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * 本地磁盘附件存储（未启用阿里云 OSS 时的回退策略，实现 {@link AttachmentStorageStrategy}）。
 * <p>
 * 仅在未启用阿里云 OSS（aliyun.oss.enabled=false）时作为唯一策略被注入；上传走后端代理——
 * 凭证 {@code uploadUrl} 指向代理端点，客户端把文件字节 PUT 到后端，由 {@link #storeBytes}
 * 写入 {@code app.attachment-local-root}（默认 {@code ./data/attachments}）；内容读取/存在性
 * 校验均按同一对象键回源本地磁盘。启用 OSS 时由 {@link AliyunOssAttachmentStorage}（@Primary）替换。
 */
@Component
public class LocalSignedAttachmentStorage implements AttachmentStorageStrategy {

    private static final String OBJECT_KEY_PATTERN = "projects/[0-9a-fA-F-]{36}/attachments/[0-9a-fA-F-]{36}";

    private final Path root;
    private final Duration expiry;

    public LocalSignedAttachmentStorage(
            @Value("${app.attachment-local-root:./data/attachments}") String root,
            @Value("${app.attachment-upload-expiry-seconds:900}") long expirySeconds) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.expiry = Duration.ofSeconds(expirySeconds);
    }

    @Override
    public String name() {
        return "LOCAL_DISK";
    }

    @Override
    public UploadCredential createCredential(UUID attachmentId, String objectKey, String fileName, String mediaType,
                                             Long sizeBytes) {
        LocalDateTime expiresAt = LocalDateTime.now(ZoneOffset.UTC).plus(expiry);
        // 代理上传：uploadUrl 指向后端代理端点（相对路径，前端按 API base origin 解析）。
        Map<String, String> headers = mediaType == null || mediaType.isBlank()
                ? Map.of()
                : Map.of("Content-Type", mediaType);
        return new UploadCredential("/api/v1/" + objectKey, "PUT", headers, expiresAt);
    }

    @Override
    public void storeBytes(String objectKey, InputStream bytes, String contentType) {
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = bytes) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to store attachment locally: " + objectKey, e);
        }
    }

    @Override
    public boolean objectExists(String objectKey) {
        return Files.exists(resolve(objectKey));
    }

    @Override
    public AttachmentContent loadContent(String objectKey, String fileName, String contentType) {
        Path target = resolve(objectKey);
        String resolvedType = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;
        Long size;
        try {
            size = Files.size(target);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read attachment from local storage: " + objectKey, e);
        }
        try {
            return new AttachmentContent(Files.newInputStream(target), resolvedType, fileName, size);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read attachment from local storage: " + objectKey, e);
        }
    }

    /**
     * 对象键解析为根目录内文件路径；非法键或路径逃逸根目录时拒绝，避免任意文件读写。
     */
    private Path resolve(String objectKey) {
        if (objectKey == null || !objectKey.matches(OBJECT_KEY_PATTERN)) {
            throw new IllegalArgumentException("Invalid attachment object key");
        }
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Attachment path escapes configured root");
        }
        return target;
    }
}
