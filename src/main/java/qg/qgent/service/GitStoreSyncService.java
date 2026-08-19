package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.entity.GitCredentialPurpose;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubBranchDetails;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitResolveRequest;
import qg.qgent.orchestration.worker.WorkerGitResolveResponse;
import qg.qgent.orchestration.worker.WorkerGitStoreSyncRequest;
import qg.qgent.orchestration.worker.WorkerGitStoreSyncResponse;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 将远端目标分支同步到 Worker Git Store 并固定为真实提交。
 * <p>
 * Dry Run、CQ+1 和创建 MR 不能只读取 Worker 的历史镜像；否则目标分支推进后，旧基线会被
 * 错当作当前门禁上下文。本服务不持有数据库事务或行锁，所有远端调用均使用一次性 FETCH 凭据。
 */
@Service
public class GitStoreSyncService {
    /**
     * source 分支 head 刷新的最小间隔：MR-first 轮询每 15s 触发一次，冲突未解决期间
     * 不需要每次轮询都打 GitHub/Worker，仅在超过该间隔后重新探测远端是否已推进。
     */
    private static final long SOURCE_HEAD_REFRESH_MIN_INTERVAL_MS = 60_000L;

    private final GitHubRepositoryMapper githubRepositories;
    private final GitHubInstallationMapper installations;
    private final GitHubAppClient github;
    private final GitCredentialService credentials;
    private final SandboxWorkerClient worker;
    private final WorkspaceRepositoryMapper workspaceRepositories;
    private final Map<String, Long> lastSourceHeadRefreshAt = new ConcurrentHashMap<>();

    public GitStoreSyncService(GitHubRepositoryMapper githubRepositories, GitHubInstallationMapper installations,
                               GitHubAppClient github, GitCredentialService credentials, SandboxWorkerClient worker,
                               WorkspaceRepositoryMapper workspaceRepositories) {
        this.githubRepositories = githubRepositories;
        this.installations = installations;
        this.github = github;
        this.credentials = credentials;
        this.worker = worker;
        this.workspaceRepositories = workspaceRepositories;
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
     * 当源分支 head 被确定性合并冲突阻塞时，探测远端是否已推进并回填 worktree head。
     * <p>
     * 用户在 GitHub 手工合并/解决冲突后不会经过 Worker push，主后端记录的 head_commit 仍为旧值，
     * 自动化会拿旧 head 反复预演出同一冲突。本方法复刻 {@link #refreshTargetBranch} 的远端同步管道，
     * 但对象是 source 分支：GitHub 取当前 SHA → 与本地 head 比对 → 一致则返回 null（无变化，不调 Worker）；
     * 不一致则签发一次性 FETCH 凭据同步 Worker、二次 resolve 校验，成功后通过
     * {@link WorkspaceRepositoryMapper#updateHeadCommit} 落库回填，返回新 head。
     * <p>
     * 本方法不持有数据库事务或行锁，所有外部调用在事务外执行；任一环节失败静默返回 null，
     * 由自动化下一次轮询重试，不阻断预检流程。
     */
    public String refreshSourceHead(UUID projectId, WorkspaceRepositoryEntity worktree,
                                    ProjectRepositoryEntity repository, UUID workspaceId) {
        if (repository == null || !projectId.equals(repository.getProjectId()) || workspaceId == null) {
            return null;
        }
        String sourceBranch = worktree == null ? null : worktree.getSourceBranch();
        String currentHead = worktree == null ? null : worktree.getHeadCommit();
        if (sourceBranch == null || sourceBranch.isBlank()
                || currentHead == null || currentHead.isBlank()) {
            return null;
        }
        GitHubRepositoryEntity githubRepository = githubRepositories.selectById(repository.getRepositoryId());
        if (githubRepository == null || !"AUTHORIZED".equals(githubRepository.getAuthorizationStatus())
                || Boolean.TRUE.equals(githubRepository.getArchived())) {
            return null;
        }
        GitHubInstallationEntity installation = installations.selectById(githubRepository.getInstallationId());
        if (installation == null || !"ACTIVE".equals(installation.getStatus())
                || installation.getProviderInstallationId() == null || installation.getTeamId() == null) {
            return null;
        }
        String throttleKey = projectId + ":" + repository.getRepositoryId() + ":" + sourceBranch + ":" + currentHead;
        long now = System.currentTimeMillis();
        Long last = lastSourceHeadRefreshAt.putIfAbsent(throttleKey, now);
        if (last != null && now - last < SOURCE_HEAD_REFRESH_MIN_INTERVAL_MS) {
            return null;
        }
        GitHubBranchDetails remote;
        try {
            remote = github.getBranch(installation.getProviderInstallationId(),
                    githubRepository.getOwnerLogin(), githubRepository.getName(), sourceBranch);
        } catch (RuntimeException failure) {
            return null;
        }
        String expectedCommit = remote == null ? null : remote.commitSha();
        if (expectedCommit == null || !expectedCommit.matches("[0-9a-fA-F]{40,64}")) {
            return null;
        }
        expectedCommit = expectedCommit.toLowerCase(Locale.ROOT);
        if (expectedCommit.equalsIgnoreCase(currentHead)) {
            return null;
        }
        String fullName = githubRepository.getOwnerLogin() + "/" + githubRepository.getName();
        try {
            String grantId = credentials.generateGrant(installation.getTeamId(), projectId,
                    installation.getProviderInstallationId(), fullName, sourceBranch, expectedCommit,
                    GitCredentialPurpose.FETCH);
            WorkerGitStoreSyncResponse synced = worker.syncGitStore(repository.getId(), new WorkerGitStoreSyncRequest()
                    .setRepositoryUrl("https://github.com/" + fullName + ".git")
                    .setRemoteBranch(sourceBranch).setExpectedHeadCommit(expectedCommit).setCredentialGrantId(grantId));
            String workerReported = synced == null ? null : synced.getHeadCommit();
            if (workerReported != null && !expectedCommit.equalsIgnoreCase(workerReported)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "GIT_SOURCE_REF_NOT_SYNCED",
                        "Worker 同步后的源分支提交与 GitHub 不一致");
            }
            WorkerGitResolveRequest resolveRequest = new WorkerGitResolveRequest();
            resolveRequest.setRepositoryId(repository.getId());
            resolveRequest.setRef(sourceBranch);
            WorkerGitResolveResponse resolved = worker.resolveGitRef(resolveRequest);
            String resolvedCommit = resolved == null ? null : resolved.getCommitSha();
            if (resolvedCommit == null || !resolvedCommit.matches("[0-9a-fA-F]{40,64}")
                    || !expectedCommit.equalsIgnoreCase(resolvedCommit)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "GIT_SOURCE_REF_NOT_SYNCED",
                        "Worker 源分支引用未刷新到 GitHub 当前提交");
            }
        } catch (RuntimeException failure) {
            // 刷新失败不阻断自动化，保持旧 head，由下一次轮询重试。
            return null;
        }
        workspaceRepositories.updateHeadCommit(workspaceId, repository.getRepositoryId(), expectedCommit);
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
