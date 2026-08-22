package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.NoArgsConstructor;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.time.LocalDateTime;
import java.util.UUID;

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
    /** GitHub App 仓库访问范围：ALL/SELECTED；NULL 表示历史记录尚未同步该字段。 */
    private String repositorySelection;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
