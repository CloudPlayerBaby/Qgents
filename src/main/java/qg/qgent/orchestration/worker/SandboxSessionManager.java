package qg.qgent.orchestration.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.*;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubBranchDetails;
import qg.qgent.mapper.*;
import qg.qgent.service.GitCredentialService;
import qg.qgent.service.WorkspaceWriteLease;
import qg.qgent.service.WorkspaceWriteLeaseService;
import qg.qgent.service.WorkBranchDevelopmentGuard;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * Sandbox 会话生命周期管理：把 Task 编排需要的"一次执行现场"映射为 Worker 的
 * 幂等 Workspace provision + 一次 Sandbox 创建/销毁。
 * <p>
 * 归属与安全边界：
 * <ul>
 *   <li>只通过 {@link WorkspaceMapper}/{@link WorkspaceRepositoryMapper} 读取已在服务端
 *       落库的 workspace 与 worktree，不自行拼接宿主机路径或 Git 远端；</li>
 *   <li>provision 请求只携带资源编号与受控 Git 引用（repositoryId/baseRef/sourceBranch/workspacePath），
 *       不提交凭证；</li>
 *   <li>{@code app.worker.enabled=false} 时本管理器为 no-op（返回 null），编排链路仍走本地端口；</li>
 *   <li>销毁只销毁 Sandbox，Workspace 持久保留。</li>
 * </ul>
 * 会话缓存仅用于定位本进程 Sandbox；真正的跨实例单写者约束由
 * {@link WorkspaceWriteLeaseService} 的持久化 CAS 租约保证；进程内同时使用
 * Workspace 级锁串行化初始化与释放。
 */
@Service
public class SandboxSessionManager {

    private static final Pattern COMMIT_SHA_PATTERN = Pattern.compile("[0-9a-fA-F]{40,64}");

    private final SandboxWorkerClient client;
    private final SandboxWorkerProperties properties;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceRepositoryMapper repositoryMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final GitHubRepositoryMapper gitHubRepositoryMapper;
    private final GitHubInstallationMapper installationMapper;
    private final GitCredentialService credentialService;
    private final GitHubAppClient githubAppClient;
    private final WorkspaceWriteLeaseService writeLeases;
    private WorkBranchDevelopmentGuard developmentGuard;
    private final Map<UUID, SandboxSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> acquireLocks = new ConcurrentHashMap<>();
    private final Map<UUID, WorkspaceWriteLease> workspaceLeases = new ConcurrentHashMap<>();

    public SandboxSessionManager(SandboxWorkerClient client, SandboxWorkerProperties properties,
                                 WorkspaceMapper workspaceMapper, WorkspaceRepositoryMapper repositoryMapper,
                                 ProjectRepositoryMapper projectRepositoryMapper, GitHubRepositoryMapper gitHubRepositoryMapper,
                                 GitHubInstallationMapper installationMapper, GitCredentialService credentialService,
                                 GitHubAppClient githubAppClient, WorkspaceWriteLeaseService writeLeases) {
        this.client = client;
        this.properties = properties;
        this.workspaceMapper = workspaceMapper;
        this.repositoryMapper = repositoryMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.gitHubRepositoryMapper = gitHubRepositoryMapper;
        this.installationMapper = installationMapper;
        this.credentialService = credentialService;
        this.githubAppClient = githubAppClient;
        this.writeLeases = writeLeases;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDevelopmentGuard(WorkBranchDevelopmentGuard developmentGuard) {
        this.developmentGuard = developmentGuard;
    }

    /**
     * 为一次 Task 编排准备 Sandbox 会话；已存在则直接返回。
     * 未启用 Worker 时返回 null（本地端口不需要会话）。
     */
    public SandboxSession acquire(UUID taskId, UUID projectId, UUID workspaceId) {
        if (!properties.isEnabled()) {
            return null;
        }
        requireWorkerWriteAllowed(projectId, workspaceId);
        ReentrantLock acquireLock = acquireLocks.computeIfAbsent(workspaceId, ignored -> new ReentrantLock());
        acquireLock.lock();
        try {
            SandboxSession existing = sessions.get(workspaceId);
            if (existing != null) {
                requireWorkerWriteAllowed(projectId, workspaceId);
                if (!existing.taskId().equals(taskId)) {
                    throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                            "WORKSPACE_WRITE_LEASE_HELD", "Workspace is currently being modified by another Task");
                }
                return existing;
            }
            WorkspaceWriteLease lease = writeLeases.acquire(projectId, workspaceId, taskId);
            try {
                SandboxSession created = doAcquire(taskId, projectId, workspaceId);
                sessions.put(workspaceId, created);
                workspaceLeases.put(workspaceId, lease);
                return created;
            } catch (RuntimeException failure) {
                writeLeases.release(lease);
                throw failure;
            }
        } finally {
            acquireLock.unlock();
        }
    }

    /**
     * 返回指定 Workspace 的当前会话；不存在时抛错，供 Worker 端口在调用工具前断言。
     */
    public SandboxSession require(UUID workspaceId) {
        SandboxSession session = sessions.get(workspaceId);
        if (session == null) {
            throw new IllegalStateException("no sandbox session for workspace " + workspaceId);
        }
        return session;
    }

    /**
     * 销毁会话对应的 Sandbox 并移除记录；Workspace 保留。销毁失败不吞结果，仅不阻断任务收尾。
     */
    public void release(UUID workspaceId, UUID taskId) {
        if (workspaceId == null || taskId == null) {
            return;
        }
        ReentrantLock acquireLock = acquireLocks.computeIfAbsent(workspaceId, ignored -> new ReentrantLock());
        acquireLock.lock();
        try {
            SandboxSession session = sessions.get(workspaceId);
            if (session == null || !taskId.equals(session.taskId())) {
                // acquire 可能因其他 Task 已持有会话而失败；只能清理自己的会话。
                return;
            }
            WorkspaceWriteLease lease = workspaceLeases.get(workspaceId);
            if (lease != null && !taskId.equals(lease.getTaskId())) {
                // 缓存不一致时宁可保留可能有效的租约，也不能释放其他 Task 的租约。
                return;
            }
            if (!sessions.remove(workspaceId, session)) {
                return;
            }
            if (lease != null) {
                workspaceLeases.remove(workspaceId, lease);
            }
            try {
                client.destroySandbox(session.sandboxId());
            } catch (RuntimeException ignored) {
                // 销毁失败由 Worker 的清理任务兜底，不阻断任务结果返回。
            } finally {
                writeLeases.release(lease);
            }
        } finally {
            acquireLock.unlock();
        }
    }

    /**
     * 每次 Worker 工具提交前验证并续租持久化写入权。租约已丢失时不能再向同一 Workspace
     * 发起命令、文件写入或测试，避免过期 Task 与后来者并发修改文件。
     */
    void renewWriteLease(UUID workspaceId) {
        WorkspaceWriteLease lease = workspaceLeases.get(workspaceId);
        SandboxSession session = sessions.get(workspaceId);
        if (lease == null || session == null || !session.taskId().equals(lease.getTaskId())) {
            throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "WORKSPACE_WRITE_LEASE_LOST", "Workspace write lease is no longer active");
        }
        requireWorkerWriteAllowed(lease.getProjectId(), workspaceId);
        writeLeases.renew(lease);
    }

    private void requireWorkerWriteAllowed(UUID projectId, UUID workspaceId) {
        if (developmentGuard != null) {
            developmentGuard.requireWorkerWriteAllowed(projectId, workspaceId);
        }
    }

    /**
     * 在整个 Task 会话存活期间维持 Sandbox 租约，覆盖 LLM 推理和阶段切换等没有工具调用的空档。
     * 单个 Sandbox 的续租失败不影响其他会话；实际工具调用会继续得到 Worker 的明确错误。
     */
    @Scheduled(fixedDelayString = "${app.worker.lease-renew-interval:10s}")
    void renewActiveLeases() {
        if (!properties.isEnabled()) {
            return;
        }
        sessions.forEach((workspaceId, session) -> {
            if (sessions.get(workspaceId) != session) {
                return;
            }
            try {
                renewWriteLease(workspaceId);
                client.renewSandbox(session.sandboxId());
            } catch (RuntimeException ignored) {
                // 当前工具调用会传播 Worker 错误；心跳不应阻塞其他活跃 Sandbox 的续租。
            }
        });
    }

    private SandboxSession doAcquire(UUID taskId, UUID projectId, UUID workspaceId) {
        WorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null || !projectId.equals(workspace.getProjectId())) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "WORKSPACE_NOT_FOUND", "Workspace does not exist or is not visible");
        }
        List<WorkspaceRepositoryEntity> repositories = repositoryMapper.selectByWorkspace(workspaceId);
        if (repositories == null || repositories.isEmpty()) {
            throw new IllegalStateException("workspace has no repository worktrees: " + workspaceId);
        }

        // 阶段一：同步所有 Git Store 并幂等准备 Workspace，瞬态失败在此阶段内自动重试。
        String storageKey = prepareWorkspace(projectId, workspaceId, workspace, repositories);

        // 阶段二：创建 Sandbox，整个 acquire 只生成一次 sandboxId，创建请求超时后按同 id 幂等恢复。
        UUID sandboxId = UuidV7.next();
        WorkerCreateSandboxRequest create = new WorkerCreateSandboxRequest();
        create.setSandboxId(sandboxId);
        create.setTaskId(taskId);
        create.setTaskRunId(taskId);
        create.setWorkspaceStorageKey(storageKey);
        create.setImageProfile(properties.getImageProfile());
        create.setRepositoryIds(repositories.stream().map(WorkspaceRepositoryEntity::getProjectRepositoryId).toList());
        createSandboxRetry(create);

        Map<String, UUID> repositoryByPath = new LinkedHashMap<>();
        for (WorkspaceRepositoryEntity repository : repositories) {
            repositoryByPath.put(repository.getWorkspacePath(), repository.getProjectRepositoryId());
        }
        return new SandboxSession(taskId, workspaceId, sandboxId, storageKey,
                create.getRepositoryIds(), Collections.unmodifiableMap(new LinkedHashMap<>(repositoryByPath)));
    }

    /**
     * 阶段一：同步所有仓库的 Git Store 并幂等准备 Workspace，返回 storageKey。
     * 整个过程至多尝试 {@code acquireMaxAttempts} 次；每次尝试都重新查询 GitHub 分支 HEAD、
     * 重新生成短期 FETCH credential，并对 sync 返回值做一致性校验。可重试错误退避后重试，
     * 不可重试错误或重试耗尽时抛出最终异常。
     */
    private String prepareWorkspace(UUID projectId, UUID workspaceId, WorkspaceEntity workspace,
                                    List<WorkspaceRepositoryEntity> repositories) {
        int attempts = properties.acquireMaxAttempts();
        long initialBackoffMillis = properties.acquireInitialBackoff().toMillis();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return prepareWorkspaceOnce(projectId, workspaceId, workspace, repositories);
            } catch (ApiException failure) {
                if (!isRetryable(failure) || attempt >= attempts) {
                    throw failure;
                }
                try {
                    sleepBackoff(initialBackoffMillis, attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("sandbox acquire interrupted during retry backoff", interrupted);
                }
            }
        }
        throw new IllegalStateException("unreachable: sandbox workspace prepare exhausted retries");
    }

    /**
     * 单次尝试：GitHub 基线查询 → 逐仓库生成短期 FETCH 凭证并同步 Git Store → 准备 Workspace。
     * 返回 Worker provision 提供的 storageKey（缺省回退到 workspace 持久字段）。
     */
    private String prepareWorkspaceOnce(UUID projectId, UUID workspaceId, WorkspaceEntity workspace,
                                        List<WorkspaceRepositoryEntity> repositories) {
        // 一次 acquire 对每个仓库只解析一次基线分支。同步 Git Store 与后续创建 worktree
        // 必须使用同一个值，避免任务指定基线与项目默认分支不同时出现“已同步 main、却从 develop 建树”。
        Map<UUID, String> baseRefByRepository = new HashMap<>();
        // 迁移前旧数据（base_commit 已是 SHA、base_ref 为空）的原基线分支名从 Worker 持久化
        // Workspace 元数据恢复；懒加载，仅存在无法本地解析的仓库时查询一次。
        Map<UUID, String> workerBaseRefs = null;

        // Fetch Grants and Sync bare Git Stores for each repository
        for (WorkspaceRepositoryEntity repository : repositories) {
            ProjectRepositoryEntity projectRepo = projectRepositoryMapper.selectById(repository.getProjectRepositoryId());
            if (projectRepo == null || !"ACTIVE".equals(projectRepo.getStatus())) {
                throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "PROJECT_REPOSITORY_NOT_BOUND",
                        "Repository binding is not active for workspace repository");
            }
            // 基线分支以不可变 base_ref 为准；兼容迁移前旧数据：base_ref 为空时回退 base_commit
            // 中的分支名（非 SHA 形态）。base_commit 已回填 SHA 后不得静默换用项目默认分支，
            // 否则会以旧 SHA 校验新分支必然失败；必须恢复原基线，恢复不了就明确报错。
            String legacyBranchRef = isCommitSha(repository.getBaseCommit()) ? null : repository.getBaseCommit();
            String configuredBaseRef = firstNonBlank(repository.getBaseRef(), legacyBranchRef);
            if ((configuredBaseRef == null || configuredBaseRef.isBlank()) && isCommitSha(repository.getBaseCommit())) {
                // 仅确已 provision 过（base_commit 为 SHA）的迁移前旧数据走恢复；
                // 从未 provision（base_commit 为空）的行直接用项目默认分支。
                if (workerBaseRefs == null) {
                    workerBaseRefs = loadWorkerBaseRefs(workspaceId);
                }
                String recovered = workerBaseRefs.get(repository.getProjectRepositoryId());
                if (recovered == null || recovered.isBlank()) {
                    throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.CONFLICT,
                            "WORKSPACE_BASE_REF_UNKNOWN",
                            "Workspace 已 provision 但原基线分支不可追溯，请重建 Workspace 后重试");
                }
                configuredBaseRef = recovered;
                persistRecoveredBaseRef(repository, recovered);
            }
            String defaultBranch = projectRepo.getDefaultBranch();
            String remoteBranch = firstNonBlank(configuredBaseRef, defaultBranch);
            if (remoteBranch == null || remoteBranch.isBlank()) {
                throw new IllegalStateException("project repository has no default branch: " + repository.getProjectRepositoryId());
            }
            baseRefByRepository.put(repository.getProjectRepositoryId(), remoteBranch);

            GitHubRepositoryEntity ghRepo = gitHubRepositoryMapper.selectById(projectRepo.getRepositoryId());
            if (ghRepo == null) {
                throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "GITHUB_REPOSITORY_NOT_FOUND",
                        "GitHub repository mirror not found");
            }
            GitHubInstallationEntity installation = installationMapper.selectById(ghRepo.getInstallationId());
            if (installation == null) {
                throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "GITHUB_INSTALLATION_NOT_FOUND",
                        "GitHub App installation not found for repository");
            }
            if (!"ACTIVE".equalsIgnoreCase(installation.getStatus()) || installation.getProviderInstallationId() == null) {
                throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_INSTALLATION_NOT_ACTIVE",
                        "GitHub App installation is not active");
            }
            if (!"AUTHORIZED".equalsIgnoreCase(ghRepo.getAuthorizationStatus())) {
                throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "GITHUB_REPOSITORY_REVOKED",
                        "GitHub repository authorization has been revoked");
            }

            // 已 provision 的行（base_commit 为 SHA）沿用钉扎的基线提交，重试不因分支推进漂移；
            // 否则按基线分支实时解析远端 HEAD。
            String expectedHeadCommit = isCommitSha(repository.getBaseCommit()) ? repository.getBaseCommit() : null;
            if (!isCommitSha(expectedHeadCommit)) {
                String owner = ghRepo.getOwnerLogin();
                String repoName = ghRepo.getName();
                GitHubBranchDetails branchDetails;
                try {
                    branchDetails = githubAppClient.getBranch(
                            installation.getProviderInstallationId(), owner, repoName, remoteBranch);
                } catch (ApiException branchFailure) {
                    // 基线分支不存在是用户可修复的确定性错误：保留稳定码与仓库/分支上下文，
                    // 供任务启动失败卡片与详情 statusReason 展示「修改基线分支后重试」，不降级为泛化错误。
                    if ("GIT_BRANCH_NOT_FOUND".equals(branchFailure.code())) {
                        throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                                "GIT_BRANCH_NOT_FOUND",
                                "仓库 " + owner + "/" + repoName + " 不存在基线分支 " + remoteBranch,
                                List.of(Map.of("repository", owner + "/" + repoName,
                                        "branch", remoteBranch, "fullName", owner + "/" + repoName)));
                    }
                    throw branchFailure;
                }
                expectedHeadCommit = branchDetails == null ? null : branchDetails.commitSha();
                if (!isCommitSha(expectedHeadCommit)) {
                    throw new IllegalStateException("GitHub branch did not return a valid HEAD commit: " + remoteBranch);
                }
            }

            String fullName = ghRepo.getOwnerLogin() + "/" + ghRepo.getName();
            String githubUrl = "https://github.com/" + fullName + ".git";

            String grantId = credentialService.generateGrant(installation.getTeamId(), projectId, installation.getProviderInstallationId(),
                    fullName, remoteBranch, expectedHeadCommit, GitCredentialPurpose.FETCH);

            WorkerGitStoreSyncRequest syncReq = new WorkerGitStoreSyncRequest()
                    .setRepositoryUrl(githubUrl)
                    .setRemoteBranch(remoteBranch)
                    .setExpectedHeadCommit(expectedHeadCommit)
                    .setCredentialGrantId(grantId);

            WorkerGitStoreSyncResponse synced = client.syncGitStore(projectRepo.getId(), syncReq);
            validateSyncResult(projectRepo.getId(), remoteBranch, expectedHeadCommit, synced);
        }

        WorkerWorkspaceProvisionRequest provision = new WorkerWorkspaceProvisionRequest();
        provision.setProjectId(projectId);
        provision.setRepositories(repositories.stream()
                .map(repository -> toRepositoryRequest(repository,
                        baseRefByRepository.get(repository.getProjectRepositoryId())))
                .toList());
        WorkerWorkspace provisioned = client.provisionWorkspace(workspaceId, provision);
        persistProvisionedCommits(repositories, provisioned);
        return provisioned != null && provisioned.getStorageKey() != null
                ? provisioned.getStorageKey() : workspace.getStorageKey();
    }

    /**
     * 校验 Git Store 同步结果是否命中预期的仓库、分支与 HEAD；任何一项不匹配视为同步失真，
     * 作为可重试错误抛出，让下一轮重新查询 HEAD 并重新同步。
     */
    private void validateSyncResult(UUID repositoryId, String remoteBranch, String expectedHeadCommit,
                                    WorkerGitStoreSyncResponse synced) {
        if (synced == null || !repositoryId.equals(synced.getRepositoryId())
                || !remoteBranch.equals(synced.getRemoteBranch())
                || !expectedHeadCommit.equalsIgnoreCase(synced.getHeadCommit())) {
            throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "GIT_STORE_SYNC_INVALID",
                    "Git store sync result did not match requested repository/branch/head");
        }
    }

    /**
     * 阶段二：创建 Sandbox，并在「创建请求超时、但请求可能已在 Worker 端生效」时按相同
     * sandboxId 查询幂等恢复。Worker 端对相同规格幂等返回，不同规格返回 {@code SANDBOX_ID_CONFLICT}。
     */
    private void createSandboxRetry(WorkerCreateSandboxRequest create) {
        int attempts = properties.acquireMaxAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                client.createSandbox(create);
                return;
            } catch (ApiException failure) {
                if (!"SANDBOX_WORKER_UNAVAILABLE".equals(failure.code())) {
                    throw failure;
                }
                WorkerSandbox existing = querySandboxOrNull(create.getSandboxId());
                if (existing != null && sameSandboxSpec(create, existing)) {
                    return;
                }
                if (existing != null) {
                    throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "SANDBOX_ID_CONFLICT",
                            "sandbox already exists with a different spec");
                }
                if (attempt >= attempts) {
                    throw failure;
                }
                try {
                    sleepBackoff(properties.acquireInitialBackoff().toMillis(), attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("sandbox create interrupted during retry backoff", interrupted);
                }
            }
        }
        throw new IllegalStateException("unreachable: sandbox create exhausted retries");
    }

    private WorkerSandbox querySandboxOrNull(UUID sandboxId) {
        try {
            return client.getSandbox(sandboxId);
        } catch (ApiException failure) {
            if ("SANDBOX_NOT_FOUND".equals(failure.code())
                    || "SANDBOX_WORKER_UNAVAILABLE".equals(failure.code())) {
                return null;
            }
            throw failure;
        }
    }

    private boolean sameSandboxSpec(WorkerCreateSandboxRequest request, WorkerSandbox existing) {
        return Objects.equals(request.getTaskRunId(), existing.getTaskRunId())
                // 旧 Worker 返回没有 taskId；缺失时按旧规格兼容恢复，不把瞬时超时升级为冲突。
                && (existing.getTaskId() == null || Objects.equals(request.getTaskId(), existing.getTaskId()))
                && Objects.equals(request.getWorkspaceStorageKey(), existing.getWorkspaceStorageKey())
                && Objects.equals(request.getImageProfile(), existing.getImageProfile())
                && existing.getRepositoryIds() != null
                && new HashSet<>(request.getRepositoryIds()).equals(new HashSet<>(existing.getRepositoryIds()));
    }

    private void sleepBackoff(long initialBackoffMillis, int attempt) throws InterruptedException {
        long multiplier = 1L << Math.min(attempt - 1, 30);
        long cappedInitial = Math.min(30_000L, initialBackoffMillis);
        long backoff = cappedInitial > 30_000L / multiplier
                ? 30_000L : cappedInitial * multiplier;
        Thread.sleep(backoff);
    }

    /**
     * 判断初始化阶段的失败是否可安全自动重试。传输层不可达与可再同步的瞬态/基线漂移错误可重试；
     * 授权、参数、规格冲突等确定性错误直接失败，不浪费重试。
     */
    private boolean isRetryable(ApiException failure) {
        return switch (failure.code()) {
            case "SANDBOX_WORKER_UNAVAILABLE", "GITHUB_API_UNAVAILABLE", "GIT_STORE_FETCH_FAILED",
                    "GIT_REMOTE_SHA_MISMATCH", "GIT_BASE_REF_NOT_FOUND", "GIT_STORE_SYNC_INVALID",
                    "GIT_COMMAND_TIMEOUT", "GIT_COMMAND_FAILED" -> true;
            default -> false;
        };
    }

    /**
     * 用 Worker provision 返回的真实基线/HEAD 提交回填 Workspace repository 持久字段。
     * base_commit 专存真实 commit SHA 供最终 Diff 校验比对；不可变基线分支名存 base_ref
     * （迁移前旧数据在回填时补写）。
     */
    private void persistProvisionedCommits(List<WorkspaceRepositoryEntity> repositories, WorkerWorkspace provisioned) {
        if (provisioned == null || provisioned.getRepositories() == null) {
            return;
        }
        for (WorkerWorkspaceRepository provisionedRepo : provisioned.getRepositories()) {
            for (WorkspaceRepositoryEntity repository : repositories) {
                if (repository.getProjectRepositoryId().equals(provisionedRepo.getRepositoryId())
                        && provisionedRepo.getBaseCommit() != null && !provisionedRepo.getBaseCommit().isBlank()) {
                    repository.setBaseCommit(provisionedRepo.getBaseCommit());
                    repository.setHeadCommit(provisionedRepo.getHeadCommit());
                    if ((repository.getBaseRef() == null || repository.getBaseRef().isBlank())
                            && provisionedRepo.getBaseRef() != null && !provisionedRepo.getBaseRef().isBlank()) {
                        repository.setBaseRef(provisionedRepo.getBaseRef());
                    }
                    repositoryMapper.updateCommits(repository.getWorkspaceId(), repository.getProjectRepositoryId(),
                            provisionedRepo.getBaseCommit(), provisionedRepo.getHeadCommit(), repository.getBaseRef());
                    break;
                }
            }
        }
    }

    private WorkerWorkspaceRepositoryRequest toRepositoryRequest(WorkspaceRepositoryEntity repository, String baseRef) {
        if (baseRef == null || baseRef.isBlank()) {
            throw new IllegalStateException("workspace repository has no resolved base ref: "
                    + repository.getProjectRepositoryId());
        }
        WorkerWorkspaceRepositoryRequest request = new WorkerWorkspaceRepositoryRequest();
        request.setRepositoryId(repository.getProjectRepositoryId());
        request.setBaseRef(baseRef);
        request.setSourceBranch(repository.getSourceBranch());
        request.setWorkspacePath(repository.getWorkspacePath());
        return request;
    }

    /**
     * 从 Worker 持久化的 Workspace 元数据读取各仓库的原基线分支名。
     * 仅用于迁移前旧数据（base_commit 已 SHA 化且 base_ref 为空）的恢复；
     * Worker 不可达时传输错误按 {@code SANDBOX_WORKER_UNAVAILABLE} 上抛，不静默降级。
     */
    private Map<UUID, String> loadWorkerBaseRefs(UUID workspaceId) {
        WorkerWorkspace persisted = client.getWorkspace(workspaceId);
        Map<UUID, String> refs = new HashMap<>();
        if (persisted != null && persisted.getRepositories() != null) {
            for (WorkerWorkspaceRepository repo : persisted.getRepositories()) {
                refs.put(repo.getRepositoryId(), repo.getBaseRef());
            }
        }
        return refs;
    }

    /**
     * 把恢复出的原基线分支名固化回 base_ref，后续 acquire 不再依赖 Worker 元数据。
     * base_commit/head_commit 原值写回，幂等无害。
     */
    private void persistRecoveredBaseRef(WorkspaceRepositoryEntity repository, String recovered) {
        repository.setBaseRef(recovered);
        repositoryMapper.updateCommits(repository.getWorkspaceId(), repository.getProjectRepositoryId(),
                repository.getBaseCommit(), repository.getHeadCommit(), recovered);
    }

    private boolean isCommitSha(String value) {
        return value != null && COMMIT_SHA_PATTERN.matcher(value).matches();
    }

    private String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : fallback;
    }
}
