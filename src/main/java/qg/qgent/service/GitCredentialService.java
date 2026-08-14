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
    private final qg.qgent.mapper.GitHubInstallationMapper installationMapper;
    private final qg.qgent.mapper.GitHubRepositoryMapper gitHubRepositoryMapper;
    private final qg.qgent.mapper.ProjectRepositoryMapper projectRepositoryMapper;

    public GitCredentialService(GitCredentialGrantMapper credentialGrantMapper, GitHubAppClient gitHubAppClient,
                                qg.qgent.mapper.GitHubInstallationMapper installationMapper,
                                qg.qgent.mapper.GitHubRepositoryMapper gitHubRepositoryMapper,
                                qg.qgent.mapper.ProjectRepositoryMapper projectRepositoryMapper) {
        this.credentialGrantMapper = credentialGrantMapper;
        this.gitHubAppClient = gitHubAppClient;
        this.installationMapper = installationMapper;
        this.gitHubRepositoryMapper = gitHubRepositoryMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
    }

    /**
     * 为 Git Push 生成一次性凭据 Grant。
     *
     * @param teamId                 团队 ID
     * @param projectId              项目 ID
     * @param providerInstallationId GitHub App 安装 ID (Provider ID)
     * @param repositoryFullName     仓库完整名称 (owner/repo)
     * @param branchName             受控分支名称
     * @param expectedHeadCommit     预期的源 HEAD
     * @return 返回生成的明文 grantId
     */
    @Transactional
    public String generateGrant(UUID teamId, UUID projectId, Long providerInstallationId,
                                String repositoryFullName, String branchName, String expectedHeadCommit) {
        return generateGrant(teamId, projectId, providerInstallationId, repositoryFullName, branchName,
                expectedHeadCommit, GitCredentialPurpose.PUSH);
    }

    /**
     * 为一项指定 Git 操作创建一次性授权；用途、仓库、分支和 HEAD 均会在兑换时原子校验。
     */
    @Transactional
    public String generateGrant(UUID teamId, UUID projectId, Long providerInstallationId,
            String repositoryFullName, String branchName, String expectedHeadCommit, GitCredentialPurpose purpose) {
        String grantId = UUID.randomUUID().toString();
        String hash = sha256(grantId);

        GitCredentialGrant grant = new GitCredentialGrant();
        grant.setGrantIdHash(hash);
        grant.setTeamId(teamId);
        grant.setProjectId(projectId);
        grant.setInstallationId(providerInstallationId); // This is providerInstallationId
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

        // 重新查出记录，以获取 installationId 和其它绑定信息进行二次校验
        GitCredentialGrant grant = credentialGrantMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<GitCredentialGrant>lambdaQuery()
                        .eq(GitCredentialGrant::getGrantIdHash, hash)
        );

        if (grant == null) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "GRANT_NOT_FOUND", "凭据记录异常");
        }

        // 校验 Installation 是否仍为 ACTIVE 且未脱离 Team
        qg.qgent.entity.GitHubInstallationEntity installation = installationMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<qg.qgent.entity.GitHubInstallationEntity>lambdaQuery()
                        .eq(qg.qgent.entity.GitHubInstallationEntity::getProviderInstallationId, grant.getInstallationId())
        );
        
        if (installation == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GITHUB_INSTALLATION_NOT_FOUND", "GitHub App 安装未找到");
        }
        if (!"ACTIVE".equalsIgnoreCase(installation.getStatus())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_INSTALLATION_NOT_ACTIVE", "GitHub App 安装已失效");
        }
        if (!installation.getTeamId().equals(grant.getTeamId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GITHUB_INSTALLATION_NOT_ACTIVE", "GitHub App 安装所属团队不匹配");
        }

        // 校验 Repository 是否仍被授权
        String[] parts = grant.getRepositoryFullName().split("/");
        if (parts.length != 2) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INVALID_REPOSITORY_NAME", "仓库名称格式错误");
        }
        String ownerLogin = parts[0];
        String repoName = parts[1];

        qg.qgent.entity.GitHubRepositoryEntity repository = gitHubRepositoryMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<qg.qgent.entity.GitHubRepositoryEntity>lambdaQuery()
                        .eq(qg.qgent.entity.GitHubRepositoryEntity::getOwnerLogin, ownerLogin)
                        .eq(qg.qgent.entity.GitHubRepositoryEntity::getName, repoName)
        );
        
        if (repository == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GITHUB_REPOSITORY_NOT_FOUND", "GitHub 仓库镜像未找到");
        }
        if (!repository.getInstallationId().equals(installation.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GITHUB_REPOSITORY_REVOKED", "GitHub 仓库与当前安装不匹配");
        }
        if (!"AUTHORIZED".equalsIgnoreCase(repository.getAuthorizationStatus())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GITHUB_REPOSITORY_REVOKED", "GitHub 仓库授权已被撤销");
        }

        // 校验 Project 与 Repository 的绑定是否依然存在
        qg.qgent.entity.ProjectRepositoryEntity projectRepo = projectRepositoryMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<qg.qgent.entity.ProjectRepositoryEntity>lambdaQuery()
                        .eq(qg.qgent.entity.ProjectRepositoryEntity::getProjectId, grant.getProjectId())
                        .eq(qg.qgent.entity.ProjectRepositoryEntity::getRepositoryId, repository.getId())
        );
        
        if (projectRepo == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROJECT_REPOSITORY_NOT_BOUND", "项目与仓库的绑定已被解除");
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
