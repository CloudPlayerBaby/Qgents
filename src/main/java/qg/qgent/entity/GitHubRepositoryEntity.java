package qg.qgent.entity;

import java.time.LocalDateTime;
import java.util.UUID;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;
import lombok.NoArgsConstructor;
import qg.qgent.handler.UuidBinaryTypeHandler;

/**
 * Local mirror of a repository exposed by a GitHub App installation. It represents GitHub metadata only,
 * not a local clone or a stored access token.
 */
@Data
@NoArgsConstructor
@TableName("github_repositories")
public class GitHubRepositoryEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    @TableField(typeHandler = UuidBinaryTypeHandler.class)
    private UUID installationId;
    private Long providerRepositoryId;
    private String ownerLogin;
    private String name;
    private String defaultBranch;
    private String visibility;
    private Boolean archived;
    private String authorizationStatus;
    private LocalDateTime syncedAt;
}
