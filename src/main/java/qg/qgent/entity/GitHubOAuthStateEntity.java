package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.time.LocalDateTime;
import java.util.UUID;

/** GitHub OAuth 一次性 state 记录；只保存 state 摘要，不保存 state 原文或 Token。 */
@Data
@TableName("github_oauth_states")
public class GitHubOAuthStateEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private byte[] stateHash;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID userId;
    private String client;
    private LocalDateTime expiresAt;
    private LocalDateTime consumedAt;
    private LocalDateTime createdAt;
}
