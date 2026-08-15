package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@TableName("git_credential_grants")
public class GitCredentialGrant {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String grantIdHash;
    private UUID teamId;
    private UUID projectId;
    private Long installationId;
    private String repositoryFullName;
    private String branchName;
    private String expectedHeadCommit;
    private GitCredentialPurpose purpose;
    private LocalDateTime expiresAt;
    private Boolean isUsed;
    private LocalDateTime createdAt;
}
