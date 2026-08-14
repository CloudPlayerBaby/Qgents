package qg.qgent.service;

import qg.qgent.dto.UploadCredential;

import java.util.UUID;

/**
 * 对象存储直传凭证策略（契约 §7 创建对象存储直传凭证）。
 * <p>
 * 上传采用「直传凭证」模式：服务端签发预签名 URL，客户端把文件字节直接 PUT 到对象存储，
 * 后端不经过文件内容；下载同样签发预签名 GET URL。当前真实实现为 {@link AliyunOssAttachmentStorage}
 * （阿里云 OSS 预签名），未启用 OSS 时回退 {@link LocalSignedAttachmentStorage}（开发用，仅签发本地端点凭证）。
 * 接入其他对象存储（MinIO/S3 等）时实现本接口并标记 {@code @Primary} 即可替换，附件元数据模型不变。
 */
public interface AttachmentStorageStrategy {

    /** 策略标识，如 {@code ALIYUN_OSS_PRESIGNED} / {@code LOCAL_SIGNED}。 */
    String name();

    /**
     * 为待上传附件签发直传凭证。
     *
     * @param attachmentId 附件 ID
     * @param objectKey    附件对象键（与 attachments.object_key 一致，下载时按此键回源）
     * @param fileName     原始文件名
     * @param mediaType    MIME 媒体类型，可空
     * @param sizeBytes    文件大小字节，可空
     * @return 直传凭证
     */
    UploadCredential createCredential(UUID attachmentId, String objectKey, String fileName, String mediaType,
            Long sizeBytes);

    /**
     * 为已上传附件签发临时下载（预签名 GET）地址。
     * <p>
     * 不支持下载的策略（如本地开发签名策略）抛 {@link UnsupportedOperationException}。
     *
     * @param objectKey      附件对象键
     * @param expiresSeconds 有效期秒数
     * @return 预签名 GET 地址
     */
    default String createDownloadUrl(String objectKey, long expiresSeconds) {
        throw new UnsupportedOperationException("当前存储策略不支持下载：storage=" + name());
    }

    /**
     * 判断对象键对应的文件是否已真实存在于存储。
     * <p>
     * 无法校验的策略按存在处理（本地开发签名策略不做校验）；真实对象存储策略（OSS）覆盖为权威校验，
     * 用于确认上传完成后置 READY。
     */
    default boolean objectExists(String objectKey) {
        return true;
    }
}
