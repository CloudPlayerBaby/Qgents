package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.dto.AvatarConfirmRequest;
import qg.qgent.dto.AvatarConfirmResponse;
import qg.qgent.dto.AvatarCredentialResponse;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.UserMapper;

import java.util.UUID;

/**
 * 用户头像上传业务：签发直传凭证 → 客户端直传 OSS → 确认并更新 users.avatar_url / avatar_object_key。
 * <p>
 * 采用「每用户单当前头像」模型：confirm 时在同一事务内 CAS 更新当前头像（对象键 + URL），并在更新后
 * 尽力删除旧 OSS 对象，避免孤儿对象累积。对象键只能指向调用者自己的 {@code avatars/{userId}/} 前缀，
 * 防止覆盖他人头像。OSS 未启用时由 {@link AvatarStorageService} 抛 501。
 */
@Service
public class AvatarService {

    private final UserMapper userMapper;
    private final AvatarStorageService storage;

    public AvatarService(UserMapper userMapper, AvatarStorageService storage) {
        this.userMapper = userMapper;
        this.storage = storage;
    }

    /**
     * 为当前用户签发头像直传凭证。
     *
     * @param userId    当前用户 ID
     * @param mediaType MIME 类型（image/*）
     * @param sizeBytes 文件大小字节
     * @return 直传凭证（含对象键），客户端上传后原样回传 objectKey 调 confirm
     */
    public AvatarCredentialResponse credential(UUID userId, String mediaType, Long sizeBytes) {
        AvatarStorageService.AvatarCredential credential = storage.createCredential(userId, mediaType, sizeBytes);
        return new AvatarCredentialResponse(credential.objectKey(), credential.credential().getUploadUrl(),
                credential.credential().getMethod(), credential.credential().getHeaders(),
                credential.credential().getExpiresAt());
    }

    /**
     * 确认头像上传并落库：校验对象属于当前用户、已真实上传，切换当前头像并尽力删除旧对象。
     *
     * @param userId 当前用户 ID
     * @param body   确认请求（objectKey 原样回传）
     * @return 头像公共读长期 URL
     */
    @Transactional
    public AvatarConfirmResponse confirm(UUID userId, AvatarConfirmRequest body) {
        String objectKey = body.getObjectKey();
        requireOwnObject(userId, objectKey);
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        }
        String previousObjectKey = user.getAvatarObjectKey();
        String publicUrl = storage.confirmAvatar(objectKey);
        user.setAvatarUrl(publicUrl);
        user.setAvatarObjectKey(objectKey);
        userMapper.updateById(user);
        if (previousObjectKey != null && !previousObjectKey.equals(objectKey)) {
            // 尽力删除旧头像对象；删除失败静默（不影响新头像已生效）
            storage.deleteObject(previousObjectKey);
        }
        return new AvatarConfirmResponse(publicUrl);
    }

    /**
     * 校验对象键必须属于当前用户自己的头像前缀，防止客户端用他人对象键覆盖他人头像。
     */
    private void requireOwnObject(UUID userId, String objectKey) {
        String ownedPrefix = "avatars/" + userId + "/";
        if (objectKey == null || !objectKey.startsWith(ownedPrefix)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AVATAR_OBJECT_KEY_FORBIDDEN",
                    "头像对象键不属于当前用户");
        }
    }
}
