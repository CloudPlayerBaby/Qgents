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
 * Agent 头像对象存储直传服务：复用阿里云 OSS 直传能力，头像对象走独立命名空间
 * {@code agents/{teamId}/{uuid}.{ext}}，确认后返回**长期稳定、公共读**的头像 URL。
 * 与用户头像（{@link AvatarStorageService}）相互独立——Agent 头像不写任何用户字段。
 * <p>
 * OSS 未启用时统一抛 501；对象键由服务端生成，confirm 校验对象键属于当前团队前缀，
 * 防止覆盖他人/他团队头像。
 */
@Component
public class AgentAvatarStorageService {

    /**
     * 头像对象大小上限（字节）。
     */
    static final long MAX_SIZE_BYTES = 5 * 1024 * 1024;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");

    private final ObjectProvider<OSS> ossProvider;
    private final AliyunOssProperties properties;

    public AgentAvatarStorageService(ObjectProvider<OSS> ossProvider, AliyunOssProperties properties) {
        this.ossProvider = ossProvider;
        this.properties = properties;
    }

    /**
     * 为团队签发 Agent 头像直传凭证：对象键 {@code agents/{teamId}/{uuid}.{ext}}，预签名 PUT。
     *
     * @param teamId    团队 ID（对象键命名空间）
     * @param mediaType MIME 类型，必须为 image/ 开头
     * @param sizeBytes 文件大小字节，必须为正且不超过上限
     * @return 直传凭证（含服务端签发的对象键，确认时原样回传）
     */
    public AgentAvatarCredential createCredential(UUID teamId, String mediaType, Long sizeBytes) {
        OSS oss = requireConfigured();
        requirePublicUrl();
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
        String objectKey = "agents/" + teamId + "/" + UuidV7.next() + "." + extension;
        Date expiresAt = new Date(System.currentTimeMillis() + properties.getPresignExpirySeconds() * 1000L);
        URL url = oss.generatePresignedUrl(properties.getBucketName(), objectKey, expiresAt, HttpMethod.PUT);
        LocalDateTime expiry = LocalDateTime.ofInstant(expiresAt.toInstant(), ZoneOffset.UTC);
        return new AgentAvatarCredential(objectKey, new UploadCredential(url.toString(), "PUT", Map.of(), expiry));
    }

    /**
     * 确认 Agent 头像已上传并返回长期公共读 URL；校验对象存在且属于该团队命名空间。
     *
     * @param teamId    团队 ID（前缀校验）
     * @param objectKey 直传凭证签发的对象键
     * @return 公共读长期稳定 URL
     */
    public String confirmAvatar(UUID teamId, String objectKey) {
        requireConfigured();
        if (objectKey == null || objectKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "AVATAR_OBJECT_KEY_REQUIRED", "缺少头像对象键");
        }
        String prefix = "agents/" + teamId + "/";
        if (!objectKey.startsWith(prefix)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AVATAR_OBJECT_KEY_FORBIDDEN",
                    "头像对象键不属于当前团队");
        }
        if (!ossProvider.getObject().doesObjectExist(properties.getBucketName(), objectKey)) {
            throw new ApiException(HttpStatus.CONFLICT, "AVATAR_NOT_UPLOADED",
                    "头像尚未上传到对象存储，请先上传再确认");
        }
        requirePublicUrl();
        return properties.getPublicBaseUrl().replaceAll("/+$", "") + "/" + objectKey;
    }

    private OSS requireConfigured() {
        if (!properties.isEnabled() || !properties.configured()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "AVATAR_STORAGE_NOT_CONFIGURED",
                    "头像上传需要启用阿里云 OSS，当前未配置");
        }
        return ossProvider.getObject();
    }

    private void requirePublicUrl() {
        if (properties.getPublicBaseUrl() == null || properties.getPublicBaseUrl().isBlank()) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED, "AVATAR_PUBLIC_URL_NOT_CONFIGURED",
                    "未配置头像公共访问基础 URL（aliyun.oss.public-base-url）");
        }
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
     * Agent 头像直传凭证：服务端签发的对象键与其对应预签名上传凭证。
     */
    public record AgentAvatarCredential(String objectKey, UploadCredential credential) {
    }
}
