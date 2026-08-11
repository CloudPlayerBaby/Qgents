package qg.qgent.service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 对象存储直传凭证策略（契约 §7 创建对象存储直传凭证）。
 * <p>
 * 项目尚未选定对象存储（S3/MinIO/OSS 等），默认使用 {@link LocalSignedAttachmentStorage}
 * 签发指向未来上传端点的签名 URL；接入真实对象存储时实现本接口并标记 {@code @Primary} 即可替换，
 * 上传 URL 变为对应存储的预签名地址，附件元数据模型不变。
 */
public interface AttachmentStorageStrategy {

    /** 策略标识，如 {@code LOCAL_SIGNED} / {@code S3_PRESIGNED}。 */
    String name();

    /**
     * 为待上传附件签发直传凭证。
     *
     * @param attachmentId 附件 ID
     * @param fileName     原始文件名
     * @param mediaType    MIME 媒体类型，可空
     * @param sizeBytes    文件大小字节，可空
     * @return 直传凭证
     */
    UploadCredential createCredential(UUID attachmentId, String fileName, String mediaType, Long sizeBytes);

    /**
     * 直传凭证。
     *
     * @param uploadUrl 上传地址（对象存储预签名地址或本地签名端点）
     * @param method    上传方法，如 PUT
     * @param headers   上传时必须携带的请求头（如 Content-Type）
     * @param expiresAt 凭证过期时间（UTC）
     */
    record UploadCredential(String uploadUrl, String method, Map<String, String> headers, LocalDateTime expiresAt) {
    }
}
