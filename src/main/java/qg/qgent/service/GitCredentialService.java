package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.entity.GitCredentialGrant;
import qg.qgent.entity.GitCredentialPurpose;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.mapper.GitCredentialGrantMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class GitCredentialService {

    private final GitCredentialGrantMapper credentialGrantMapper;
    private final GitHubAppClient gitHubAppClient;

    public GitCredentialService(GitCredentialGrantMapper credentialGrantMapper, GitHubAppClient gitHubAppClient) {
        this.credentialGrantMapper = credentialGrantMapper;
        this.gitHubAppClient = gitHubAppClient;
    }

    /**
     * 为 Git Push 生成一次性凭据 Grant。
     *
     * @param teamId             团队 ID
     * @param projectId          项目 ID
     * @param installationId     GitHub App 安装 ID
     * @param repositoryFullName 仓库完整名称 (owner/repo)
     * @param branchName         受控分支名称
     * @param expectedHeadCommit 预期的源 HEAD
     * @return 返回生成的明文 grantId
     */
    @Transactional
    public String generateGrant(UUID teamId, UUID projectId, Long installationId,
                                String repositoryFullName, String branchName, String expectedHeadCommit) {
        return generateGrant(teamId, projectId, installationId, repositoryFullName, branchName,
                expectedHeadCommit, GitCredentialPurpose.PUSH);
    }

    /**
     * 为一项指定 Git 操作创建一次性授权；用途、仓库、分支和 HEAD 均会在兑换时原子校验。
     */
    @Transactional
    public String generateGrant(UUID teamId, UUID projectId, Long installationId,
            String repositoryFullName, String branchName, String expectedHeadCommit, GitCredentialPurpose purpose) {
        String grantId = UUID.randomUUID().toString();
        String hash = sha256(grantId);

        GitCredentialGrant grant = new GitCredentialGrant();
        grant.setGrantIdHash(hash);
        grant.setTeamId(teamId);
        grant.setProjectId(projectId);
        grant.setInstallationId(installationId);
        grant.setRepositoryFullName(repositoryFullName);
        grant.setBranchName(branchName);
        grant.setExpectedHeadCommit(expectedHeadCommit);
        grant.setPurpose(purpose);
        
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        grant.setCreatedAt(now);
        grant.setExpiresAt(now.plusSeconds(60)); // 60秒过期
        grant.setIsUsed(false);

        credentialGrantMapper.insert(grant);
        return grantId;
    }

    /**
     * Worker 兑换凭据，成功后返回真实的 GitHub Installation Token。
     * 利用数据库行级锁保证仅可兑换一次。
     *
     * @param grantId            明文的凭据 grantId
     * @param expectedHeadCommit Worker 端的当前 HEAD
     * @return GitHub Installation Token
     */
    @Transactional
    public String exchangeGrant(String grantId, String expectedHeadCommit, String repositoryFullName,
            String branchName, GitCredentialPurpose purpose) {
        String hash = sha256(grantId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        int updated = credentialGrantMapper.exchangeGrant(hash, expectedHeadCommit, repositoryFullName, branchName,
                purpose, now);
        if (updated == 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "INVALID_CREDENTIAL_GRANT",
                    "临时凭据无效、已过期、已被使用，或 HEAD 不匹配");
        }

        // 重新查出记录，以获取 installationId
        GitCredentialGrant grant = credentialGrantMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<GitCredentialGrant>lambdaQuery()
                        .eq(GitCredentialGrant::getGrantIdHash, hash)
        );

        if (grant == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "GRANT_NOT_FOUND", "凭据记录异常");
        }

        return gitHubAppClient.createInstallationToken(grant.getInstallationId());
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing SHA-256 algorithm", e);
        }
    }
}
