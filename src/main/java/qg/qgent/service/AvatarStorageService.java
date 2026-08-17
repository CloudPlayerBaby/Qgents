package qg.qgent.service;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.config.AliyunOssProperties;
import qg.qgent.dto.UploadCredential;

import java.net.URL;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 用户头像对象存储直传服务：复用阿里云 OSS 直传能力（预签名 PUT），但头像对象走独立命名空间
 * {@code avatars/{userId}/...}，并在确认后返回**长期稳定、公共读**的头像 URL（由
 * {@code aliyun.oss.public-base-url} 拼接，不依赖项目鉴权）。与项目附件（{@link AttachmentStorageStrategy}）
 * 相互独立——附件是项目私有的短期预签名地址，头像需要跨项目公共可读。
 * <p>
 * OSS 未启用（{@code aliyun.oss.enabled=false}，本地/CI）时不创建 OSS 客户端，本服务方法统一抛
 * {@code 501 AVATAR_STORAGE_NOT_CONFIGURED}，由 {@code qg.qgent.controller.AvatarController} 映射为
 * 「头像上传暂不可用」。对象键完全由服务端生成，不使用客户端文件名，避免路径穿越/覆盖他人头像/向
 * 公共读前缀写入非图片内容。
 */
@Component
public class AvatarStorageService {

    /**
     * 头像对象大小上限（字节），前端应同步限制图片体积。
     */
    static final long MAX_SIZE_BYTES = 5 * 1024 * 1024;

    /**
     * 允许的头像扩展名白名单。
     */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private final ObjectProvider<OSS> ossProvider;
    private final AliyunOssProperties properties;

    public AvatarStorageService(ObjectProvider<OSS> ossProvider, AliyunOssProperties properties) {
        this.ossProvider = ossProvider;
        this.properties = properties;
    }

    /**
     * 为当前用户签发头像直传凭证：服务端生成 {@code avatars/{userId}/{uuid}.{ext}} 对象键并签发预签名 PUT；
     * 校验媒体类型必须是图片、大小在上限内。OSS 未启用时抛 501。
     *
     * @param userId    当前用户 ID（对象键命名空间，用户只能写自己的头像）
     * @param mediaType MIME 类型，必须为 image/ 开头
     * @param sizeBytes 文件大小字节，必须为正且不超过上限
     * @return 直传凭证（含服务端签发的对象键，客户端确认时原样回传）
     */
    public AvatarCredential createCredential(UUID userId, String mediaType, Long sizeBytes) {
        OSS oss = requireConfigured();
        String normalizedMediaType = mediaType == null ? "" : mediaType.trim().toLowerCase(Locale.ROOT);
        if (!normalizedMediaType.startsWith("image/")) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "AVATAR_MEDIA_TYPE_REJECTED",
                    "头像必须是图片（image/*）");
        }
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_SIZE_REQUIRED", "头像大小必须大于 0");
        }
        if (sizeBytes > MAX_SIZE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "AVATAR_TOO_LARGE",
                    "头像大小超过上限 " + MAX_SIZE_BYTES + " 字节");
        }
        String extension = extensionFor(normalizedMediaType);
        String objectKey = "avatars/" + userId + "/" + UuidV7.next() + "." + extension;
        Date expiresAt = new Date(System.currentTimeMillis() + properties.getPresignExpirySeconds() * 1000L);
        URL url = oss.generatePresignedUrl(properties.getBucketName(), objectKey, expiresAt, HttpMethod.PUT);
        LocalDateTime expiry = LocalDateTime.ofInstant(expiresAt.toInstant(), ZoneOffset.UTC);
        return new AvatarCredential(objectKey, new UploadCredential(url.toString(), "PUT", Map.of(), expiry));
    }

    /**
     * 确认头像已上传，返回其长期公共读 URL。校验对象确已存在于 OSS（未上传返回 409）。
     *
     * @param objectKey 直传凭证签发的对象键
     * @return 公共读长期稳定 URL
     */
    public String confirmAvatar(String objectKey) {
        requireConfigured();
        if (objectKey == null || objectKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_OBJECT_KEY_REQUIRED", "缺少头像对象键");
        }
        if (!ossProvider.getObject().doesObjectExist(properties.getBucketName(), objectKey)) {
            throw new ApiException(HttpStatus.CONFLICT, "AVATAR_NOT_UPLOADED",
                    "头像尚未上传到对象存储，请先上传再确认");
        }
        if (properties.getPublicBaseUrl() == null || properties.getPublicBaseUrl().isBlank()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "AVATAR_PUBLIC_URL_NOT_CONFIGURED",
                    "未配置头像公共访问基础 URL（aliyun.oss.public-base-url）");
        }
        return properties.getPublicBaseUrl().replaceAll("/+$", "") + "/" + objectKey;
    }

    /**
     * 尽力删除旧头像对象；OSS 未启用时 no-op。删除失败不抛出（不影响头像切换成功）。
     */
    public void deleteObject(String objectKey) {
        if (!properties.isEnabled() || properties.getBucketName().isBlank()
                || objectKey == null || objectKey.isBlank()) {
            return;
        }
        try {
            ossProvider.getObject().deleteObject(properties.getBucketName(), objectKey);
        } catch (RuntimeException e) {
            // 旧头像删除是尽力而为，失败不影响新头像已生效
        }
    }

    private OSS requireConfigured() {
        if (!properties.isEnabled() || !properties.configured()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "AVATAR_STORAGE_NOT_CONFIGURED",
                    "头像上传需要启用阿里云 OSS，当前未配置");
        }
        return ossProvider.getObject();
    }

    private String extensionFor(String mediaType) {
        String subtype = mediaType.startsWith("image/") ? mediaType.substring("image/".length()).trim() : "";
        String ext = subtype.equals("jpeg") ? "jpg" : subtype;
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "AVATAR_MEDIA_TYPE_REJECTED",
                    "不支持的图片类型: " + mediaType);
        }
        return ext;
    }

    /**
     * 头像直传凭证：服务端签发的对象键与其对应预签名上传凭证。
     */
    public record AvatarCredential(String objectKey, UploadCredential credential) {
    }
}
