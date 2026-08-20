package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.time.LocalDateTime;
import java.util.UUID;

/** 当前 Qgents 用户关联的 GitHub OAuth 授权；Token 仅保存 AES-GCM 密文。 */
@Data
@TableName("github_user_authorizations")
public class GitHubUserAuthorizationEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID userId;
    private String provider;
    private Long providerUserId;
    private String providerLogin;
    /** 撤销成功或外部失效时需写回 NULL，因此 update 策略为 ALWAYS，避免 MyBatis-Plus 忽略 null 字段。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String accessTokenCiphertext;
    private String scopes;
    private String status;
    /** 重新授权成功时需清空旧错误码，update 策略为 ALWAYS。 */
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastErrorCode;
    private LocalDateTime authorizedAt;
    private LocalDateTime lastValidatedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
