package qg.qgent.orchestration.worker;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.service.GitCredentialService;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitCredentialPurpose;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubBranchDetails;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
 * 当前编排是同步单线程、单 Workspace 单写者，会话用进程内 Map 按 workspaceId 记录；
 * 若未来接入并行执行，写入租约必须升级为持久状态/锁（见 AGENTS.md）。
 */
@Service
public class SandboxSessionManager {

    private final SandboxWorkerClient client;
    private final SandboxWorkerProperties properties;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceRepositoryMapper repositoryMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final GitHubRepositoryMapper gitHubRepositoryMapper;
    private final GitHubInstallationMapper installationMapper;
    private final GitCredentialService credentialService;
    private final GitHubAppClient githubAppClient;
    private final Map<UUID, SandboxSession> sessions = new ConcurrentHashMap<>();

    public SandboxSessionManager(SandboxWorkerClient client, SandboxWorkerProperties properties,
            WorkspaceMapper workspaceMapper, WorkspaceRepositoryMapper repositoryMapper,
            ProjectRepositoryMapper projectRepositoryMapper, GitHubRepositoryMapper gitHubRepositoryMapper,
            GitHubInstallationMapper installationMapper, GitCredentialService credentialService,
            GitHubAppClient githubAppClient) {
        this.client = client;
        this.properties = properties;
        this.workspaceMapper = workspaceMapper;
        this.repositoryMapper = repositoryMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.gitHubRepositoryMapper = gitHubRepositoryMapper;
        this.installationMapper = installationMapper;
        this.credentialService = credentialService;
        this.githubAppClient = githubAppClient;
    }

    /**
     * 为一次 Task 编排准备 Sandbox 会话；已存在则直接返回。
     * 未启用 Worker 时返回 null（本地端口不需要会话）。
     */
    public SandboxSession acquire(UUID taskId, UUID projectId, UUID workspaceId) {
        if (!properties.isEnabled()) {
            return null;
        }
        synchronized (sessions) {
            SandboxSession existing = sessions.get(workspaceId);
            if (existing != null) {
                return existing;
            }
            SandboxSession created = doAcquire(taskId, projectId, workspaceId);
            sessions.put(workspaceId, created);
            return created;
        }
    }

    /** 返回指定 Workspace 的当前会话；不存在时抛错，供 Worker 端口在调用工具前断言。 */
    public SandboxSession require(UUID workspaceId) {
        SandboxSession session = sessions.get(workspaceId);
        if (session == null) {
            throw new IllegalStateException("no sandbox session for workspace " + workspaceId);
        }
        return session;
    }

    /** 销毁会话对应的 Sandbox 并移除记录；Workspace 保留。销毁失败不吞结果，仅不阻断任务收尾。 */
    public void release(UUID workspaceId) {
        SandboxSession session = sessions.remove(workspaceId);
        if (session == null) {
            return;
        }
        try {
            client.destroySandbox(session.getSandboxId());
        } catch (RuntimeException ignored) {
            // 销毁失败由 Worker 的清理任务兜底，不阻断任务结果返回。
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
                client.renewSandbox(session.getSandboxId());
            } catch (RuntimeException ignored) {
                // 当前工具调用会传播 Worker 错误；心跳不应阻塞其他活跃 Sandbox 的续租。
            }
        });
    }

    private SandboxSession doAcquire(UUID taskId, UUID projectId, UUID workspaceId) {
        WorkspaceEntity workspace = workspaceMapper.selectById(workspaceId);
        if (workspace == null) {
            throw new IllegalStateException("workspace not found: " + workspaceId);
        }
        List<WorkspaceRepositoryEntity> repositories = repositoryMapper.selectByWorkspace(workspaceId);
        if (repositories == null || repositories.isEmpty()) {
            throw new IllegalStateException("workspace has no repository worktrees: " + workspaceId);
        }

        // Fetch Grants and Sync bare Git Stores for each repository
        for (WorkspaceRepositoryEntity repository : repositories) {
            ProjectRepositoryEntity projectRepo = projectRepositoryMapper.selectById(repository.getProjectRepositoryId());
            if (projectRepo == null) {
                throw new qg.qgent.api.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "PROJECT_REPOSITORY_NOT_BOUND",
                        "Repository binding not found for workspace repository");
            }
            String remoteBranch = projectRepo.getDefaultBranch();
            if (remoteBranch == null || remoteBranch.isBlank()) {
                throw new IllegalStateException("project repository has no default branch: " + repository.getProjectRepositoryId());
            }

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
            
            String expectedHeadCommit = repository.getBaseCommit();
            if (expectedHeadCommit == null || expectedHeadCommit.isBlank()) {
                GitHubBranchDetails branchDetails = githubAppClient.getBranch(
                        installation.getProviderInstallationId(), ghRepo.getOwnerLogin(), ghRepo.getName(), remoteBranch);
                expectedHeadCommit = branchDetails.commitSha();
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
            
            client.syncGitStore(projectRepo.getId(), syncReq);
        }

        WorkerWorkspaceProvisionRequest provision = new WorkerWorkspaceProvisionRequest();
        provision.setProjectId(projectId);
        provision.setRepositories(repositories.stream().map(this::toRepositoryRequest).toList());
        WorkerWorkspace provisioned = client.provisionWorkspace(workspaceId, provision);
        String storageKey = provisioned != null && provisioned.getStorageKey() != null
                ? provisioned.getStorageKey() : workspace.getStorageKey();

        UUID sandboxId = UuidV7.next();
        WorkerCreateSandboxRequest create = new WorkerCreateSandboxRequest();
        create.setSandboxId(sandboxId);
        create.setTaskRunId(taskId);
        create.setWorkspaceStorageKey(storageKey);
        create.setImageProfile(properties.getImageProfile());
        create.setRepositoryIds(repositories.stream().map(WorkspaceRepositoryEntity::getProjectRepositoryId).toList());
        client.createSandbox(create);

        Map<String, UUID> repositoryByPath = new LinkedHashMap<>();
        for (WorkspaceRepositoryEntity repository : repositories) {
            repositoryByPath.put(repository.getWorkspacePath(), repository.getProjectRepositoryId());
        }
        return new SandboxSession(taskId, workspaceId, sandboxId, storageKey,
                create.getRepositoryIds(), Collections.unmodifiableMap(new LinkedHashMap<>(repositoryByPath)));
    }

    private WorkerWorkspaceRepositoryRequest toRepositoryRequest(WorkspaceRepositoryEntity repository) {
        WorkerWorkspaceRepositoryRequest request = new WorkerWorkspaceRepositoryRequest();
        request.setRepositoryId(repository.getProjectRepositoryId());
        request.setBaseRef(resolveBaseRef(repository));
        request.setSourceBranch(repository.getSourceBranch());
        request.setWorkspacePath(repository.getWorkspacePath());
        return request;
    }

    /**
     * 解析仓库基线引用：worktree 已记录 baseCommit 时使用它；否则回退到项目仓库绑定的
     * defaultBranch（真实受控引用）。两者都缺失时明确失败，不伪造基线。
     */
    private String resolveBaseRef(WorkspaceRepositoryEntity repository) {
        if (repository.getBaseCommit() != null && !repository.getBaseCommit().isBlank()) {
            return repository.getBaseCommit();
        }
        ProjectRepositoryEntity projectRepository = projectRepositoryMapper.selectById(repository.getProjectRepositoryId());
        if (projectRepository != null && projectRepository.getDefaultBranch() != null
                && !projectRepository.getDefaultBranch().isBlank()) {
            return projectRepository.getDefaultBranch();
        }
        throw new IllegalStateException("workspace repository has no base ref: "
                + repository.getProjectRepositoryId());
    }
}
