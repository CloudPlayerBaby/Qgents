package qg.qgent.entity;

import java.time.Instant;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.NoArgsConstructor;
import qg.qgent.handler.UuidBinaryTypeHandler;

/**
 * GitHub App installation metadata authorized by one team. The mapped table stores no installation token;
 * identifiers use the repository-wide {@code BINARY(16)} UUID representation.
 */
@Data
@NoArgsConstructor
@TableName("github_installations")
public class GitHubInstallationEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID teamId;
    private Long providerInstallationId;
    private String accountLogin;
    private String accountType;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
}
