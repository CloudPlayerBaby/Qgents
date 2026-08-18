package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.entity.GitCredentialPurpose;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubBranchDetails;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitResolveRequest;
import qg.qgent.orchestration.worker.WorkerGitResolveResponse;
import qg.qgent.orchestration.worker.WorkerGitStoreSyncRequest;
import qg.qgent.orchestration.worker.WorkerGitStoreSyncResponse;

import java.util.Locale;
import java.util.UUID;

/**
 * 将远端目标分支同步到 Worker Git Store 并固定为真实提交。
 * <p>
 * Dry Run、CQ+1 和创建 MR 不能只读取 Worker 的历史镜像；否则目标分支推进后，旧基线会被
 * 错当作当前门禁上下文。本服务不持有数据库事务或行锁，所有远端调用均使用一次性 FETCH 凭据。
 */
@Service
public class GitStoreSyncService {
    private final GitHubRepositoryMapper githubRepositories;
    private final GitHubInstallationMapper installations;
    private final GitHubAppClient github;
    private final GitCredentialService credentials;
    private final SandboxWorkerClient worker;

    public GitStoreSyncService(GitHubRepositoryMapper githubRepositories, GitHubInstallationMapper installations,
                               GitHubAppClient github, GitCredentialService credentials, SandboxWorkerClient worker) {
        this.githubRepositories = githubRepositories;
        this.installations = installations;
        this.github = github;
        this.credentials = credentials;
        this.worker = worker;
    }

    /**
     * 获取 GitHub 当前 targetBranch 的 SHA，同步到 Worker 后再次解析校验，返回该固定 SHA。
     */
    public String refreshTargetBranch(UUID projectId, ProjectRepositoryEntity repository, String targetBranch) {
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "仓库不存在或不可见");
        }
        String branch = normalizeTargetBranch(targetBranch);
        GitHubRepositoryEntity githubRepository = githubRepositories.selectById(repository.getRepositoryId());
        if (githubRepository == null || !"AUTHORIZED".equals(githubRepository.getAuthorizationStatus())
                || Boolean.TRUE.equals(githubRepository.getArchived())) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_REPOSITORY_UNAVAILABLE", "GitHub 仓库不可用于同步目标分支");
        }
        GitHubInstallationEntity installation = installations.selectById(githubRepository.getInstallationId());
        if (installation == null || !"ACTIVE".equals(installation.getStatus())
                || installation.getProviderInstallationId() == null || installation.getTeamId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_INSTALLATION_UNAVAILABLE", "GitHub 安装不可用于同步目标分支");
        }
        GitHubBranchDetails remote = github.getBranch(installation.getProviderInstallationId(),
                githubRepository.getOwnerLogin(), githubRepository.getName(), branch);
        String expectedCommit = validSha(remote == null ? null : remote.commitSha(), "GITHUB_BRANCH_SHA_INVALID",
                "GitHub 未返回有效的目标分支提交");
        String fullName = githubRepository.getOwnerLogin() + "/" + githubRepository.getName();
        String grantId = credentials.generateGrant(installation.getTeamId(), projectId,
                installation.getProviderInstallationId(), fullName, branch, expectedCommit, GitCredentialPurpose.FETCH);
        WorkerGitStoreSyncResponse synced = worker.syncGitStore(repository.getId(), new WorkerGitStoreSyncRequest()
                .setRepositoryUrl("https://github.com/" + fullName + ".git")
                .setRemoteBranch(branch).setExpectedHeadCommit(expectedCommit).setCredentialGrantId(grantId));
        String workerReported = synced == null ? null : synced.getHeadCommit();
        if (workerReported != null && !expectedCommit.equalsIgnoreCase(workerReported)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GIT_BASE_REF_NOT_SYNCED",
                    "Worker 同步后的目标分支提交与 GitHub 不一致");
        }
        WorkerGitResolveRequest resolveRequest = new WorkerGitResolveRequest();
        resolveRequest.setRepositoryId(repository.getId());
        resolveRequest.setRef(branch);
        WorkerGitResolveResponse resolved = worker.resolveGitRef(resolveRequest);
        String resolvedCommit = validSha(resolved == null ? null : resolved.getCommitSha(), "GIT_BASE_REF_NOT_SYNCED",
                "Worker 未能解析刚同步的目标分支");
        if (!expectedCommit.equals(resolvedCommit)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GIT_BASE_REF_NOT_SYNCED",
                    "Worker 目标分支引用未刷新到 GitHub 当前提交");
        }
        return expectedCommit;
    }

    /**
     * 将客户端提供的目标分支收敛为可用于 GitHub、Git Store 和数据库门禁查询的唯一形式。
     *
     * 入口层不能各自 trim 或各自校验，否则同一分支可能在同步时为 {@code main}、
     * 在 Dry Run 查询时却为 {@code " main "}，使已通过的预检无法命中。
     */
    public String normalizeTargetBranch(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_BRANCH", "目标分支不能为空");
        }
        String branch = value.trim();
        if (!branch.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}") || branch.startsWith("/")
                || branch.contains("//") || branch.contains("..") || branch.endsWith(".lock")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_BRANCH", "目标分支格式不合法");
        }
        return branch;
    }

    private String validSha(String value, String code, String message) {
        if (value == null || !value.matches("[0-9a-fA-F]{40,64}")) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, code, message);
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
