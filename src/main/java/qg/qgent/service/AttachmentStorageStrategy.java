package qg.qgent.service;

import qg.qgent.dto.UploadCredential;

import java.io.InputStream;
import java.util.UUID;

/**
 * 附件上传/下载存储策略（契约 §7 附件直传凭证）。
 * <p>
 * 上传采用「服务端代理」模式：凭证 {@code uploadUrl} 指向后端代理上传端点（/api/v1/.../attachments/{id}），
 * 客户端把文件字节 PUT 到后端，后端经 {@link #storeBytes} 写入真实存储（阿里云 OSS 或本地磁盘）；
 * 下载/内容读取签发预签名 GET URL 或直接流式回源。当前真实实现为 {@link AliyunOssAttachmentStorage}
 * （阿里云 OSS），未启用 OSS 时回退 {@link LocalSignedAttachmentStorage}（本地磁盘）。
 * 接入其他对象存储（MinIO/S3 等）时实现本接口并标记 {@code @Primary} 即可替换，附件元数据模型不变。
 */
public interface AttachmentStorageStrategy {

    /**
     * 策略标识，如 {@code ALIYUN_OSS_PRESIGNED} / {@code LOCAL_SIGNED}。
     */
    String name();

    /**
     * 为待上传附件签发直传凭证。
     * <p>
     * 返回后端代理上传端点（相对路径 {@code /api/v1/<objectKey>}，前端按 API base origin 解析），
     * 客户端据此把文件字节 PUT 到后端，由 {@link #storeBytes} 写入存储。
     *
     * @param attachmentId 附件 ID
     * @param objectKey    附件对象键（与 attachments.object_key 一致，上传/回源均按此键）
     * @param fileName     原始文件名
     * @param mediaType    MIME 媒体类型，可空
     * @param sizeBytes    文件大小字节，可空
     * @return 直传凭证
     */
    UploadCredential createCredential(UUID attachmentId, String objectKey, String fileName, String mediaType,
                                      Long sizeBytes);

    /**
     * 写入附件字节（服务端代理上传落盘/上传对象存储）。
     * <p>
     * 不支持服务端接收的策略抛 {@link UnsupportedOperationException}（当前双实现均支持，为扩展预留）。
     *
     * @param objectKey   附件对象键
     * @param bytes       文件字节流（调用方负责读取并校验大小上限）
     * @param contentType MIME 类型，可空
     */
    default void storeBytes(String objectKey, InputStream bytes, String contentType) {
        throw new UnsupportedOperationException("当前存储策略不支持服务端接收上传：storage=" + name());
    }

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

    /**
     * 鉴权后流式读取对象内容（供内容下载代理使用）。
     * <p>
     * 不支持流式读取的策略（如本地开发签名策略）抛 {@link UnsupportedOperationException}，
     * 调用方据此返回 501。
     *
     * @param objectKey   附件对象键
     * @param fileName    原始文件名（用于响应头）
     * @param contentType MIME 类型，可空
     * @return 内容流与元数据
     */
    default AttachmentContent loadContent(String objectKey, String fileName, String contentType) {
        throw new UnsupportedOperationException("当前存储策略不支持流式读取：storage=" + name());
    }
}
