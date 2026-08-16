package qg.qgent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.github.*;
import qg.qgent.mapper.*;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * GitHub 仓库与团队/项目授权绑定服务。
 * 负责处理 GitHub App 的安装、卸载、仓库同步，以及仓库与具体 Qgents 项目的绑定与解绑逻辑。
 */
@Service
@Slf4j
public class GitHubRepositoryService {
    private static final ConcurrentMap<Long, Object> INSTALLATION_CALLBACK_LOCKS = new ConcurrentHashMap<>();
    private final GitHubInstallationMapper installationMapper;
    private final GitHubRepositoryMapper repositoryMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final RepositoryBranchConfigMapper branchConfigMapper;
    private final GitHubAppClient gitHubClient;
    private final Clock clock;
    private final TransactionTemplate required;

    public GitHubRepositoryService(GitHubInstallationMapper installationMapper, GitHubRepositoryMapper repositoryMapper,
                                   ProjectRepositoryMapper projectRepositoryMapper,
                                   ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper,
                                   TeamMemberMapper teamMemberMapper, RepositoryBranchConfigMapper branchConfigMapper,
                                   GitHubAppClient gitHubClient, Clock clock,
                                   PlatformTransactionManager transactionManager) {
        this.installationMapper = installationMapper;
        this.repositoryMapper = repositoryMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.branchConfigMapper = branchConfigMapper;
        this.gitHubClient = gitHubClient;
        this.clock = clock;
        this.required = new TransactionTemplate(transactionManager);
        this.required.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * 为团队生成 GitHub App 安装的授权跳转链接。
     * 只有 Team Owner 才能执行此操作。
     *
     * @param actorId 当前操作用户的 ID
     * @param teamId  团队 ID
     * @return 包含安装跳转 URL 和过期时间的响应对象
     */
    public GitHubInstallationUrlResponse createInstallationUrl(UUID actorId, UUID teamId) {
        log.info("Generating GitHub installation URL for teamId: {}, requested by actorId: {}", teamId, actorId);
        requireTeamOwner(actorId, teamId);
        return new GitHubInstallationUrlResponse(gitHubClient.createInstallationUrl(teamId, actorId),
                OffsetDateTime.now(clock).plusSeconds(600));
    }

    public GitHubInstallationUrlResponse createInstallationUrl(UUID actorId, UUID teamId, GitHubClient client) {
        log.info("Generating GitHub installation URL for teamId: {}, requested by actorId: {}", teamId, actorId);
        // 需要是团队所有者
        requireTeamOwner(actorId, teamId);
        // 调用底层 GitHub SDK 生成包含状态（加密的 teamId）的安装链接，时效为 10 分钟
        return new GitHubInstallationUrlResponse(gitHubClient.createInstallationUrl(teamId, actorId, client),
                OffsetDateTime.now(clock).plusSeconds(600));
    }

    /**
     * 列出指定团队已安装的 GitHub App 授权记录。
     * 只有 Team Owner 才能执行此操作。
     *
     * @param actorId 操作人的用户 ID
     * @param teamId  团队 ID
     * @return 包含该团队所有有效安装记录的响应列表
     */
    public List<GitHubInstallationResponse> listInstallations(UUID actorId, UUID teamId) {
        // 权限校验：必须是团队所有者
        requireTeamOwner(actorId, teamId);
        // 查询数据库中属于该团队的安装记录，按更新时间倒序排列
        requireTeamOwner(actorId, teamId);
        // 返回团队的所有安装
        return installationMapper.selectList(new LambdaQueryWrapper<GitHubInstallationEntity>()
                        .eq(GitHubInstallationEntity::getTeamId, teamId)
                        .orderByDesc(GitHubInstallationEntity::getUpdatedAt))
                .stream().map(this::toInstallationResponse).toList();
    }

    /**
     * 移除团队已安装的 GitHub App 授权记录。
     * 如果该安装下的仓库已经被绑定到某个具体项目里，则拒绝删除（需要先解绑项目仓库）。
     *
     * @param actorId        操作人 ID
     * @param teamId         团队 ID
     * @param installationId Qgents 内部的安装记录 ID
     */
    @Transactional
    public void removeInstallation(UUID actorId, UUID teamId, UUID installationId) {
        // 需要是团队所有者
        requireTeamOwner(actorId, teamId);

        // 查找属于该团队的这条安装记录
        GitHubInstallationEntity installation = installationMapper.selectOne(new LambdaQueryWrapper<GitHubInstallationEntity>()
                .eq(GitHubInstallationEntity::getId, installationId)
                .eq(GitHubInstallationEntity::getTeamId, teamId));
        if (installation == null) {
            throw notFound("GitHub installation does not exist");
        }

        // 查询该安装记录下同步过来的所有仓库 ID
        List<UUID> repositoryIds = repositoryMapper.selectList(new LambdaQueryWrapper<GitHubRepositoryEntity>()
                        .eq(GitHubRepositoryEntity::getInstallationId, installationId)
                        .select(GitHubRepositoryEntity::getId))
                .stream().map(GitHubRepositoryEntity::getId).toList();

        // 如果有仓库，并且这些仓库有任何一个正在被某个项目绑定使用，就抛出冲突异常
        if (!repositoryIds.isEmpty() && projectRepositoryMapper.selectCount(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                .in(ProjectRepositoryEntity::getRepositoryId, repositoryIds)) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_INSTALLATION_IN_USE",
                    "Unbind project repositories before removing this GitHub installation");
        }

        // 只有所有仓库都没被项目引用时，才允许物理删除这条安装记录
        installationMapper.deleteById(installationId);
    }

    /**
     * 列出团队可访问的所有已同步的 GitHub 仓库。
     * 调用者需要是 Team Owner 或者该团队下任一项目的 Project Admin。
     *
     * @param actorId 操作人的用户 ID
     * @param teamId  团队 ID
     * @return 该团队下所有处于 ACTIVE 状态的安装记录所关联的仓库列表
     */
    public List<GitHubRepositoryResponse> listTeamRepositories(UUID actorId, UUID teamId) {
        // 权限校验：验证操作者在团队内的权限等级
        if (!hasTeamRepositoryAccess(teamId, actorId)) {
            throw forbidden("Team owner or project admin access is required");
        }
        // 获取所有激活状态的仓库，并转换为 DTO 返回
        return findActiveRepositoriesByTeam(teamId).stream().map(this::toRepositoryResponse).toList();
    }

    /**
     * 列出某个具体项目所绑定的所有 GitHub 仓库。
     * 调用者只需具备该项目的 Project Member 权限即可查看。
     *
     * @param actorId   操作人的用户 ID
     * @param projectId 项目 ID
     * @return 该项目已绑定的仓库详情列表（含绑定记录及底层仓库数据）
     */
    public List<ProjectRepositoryResponse> listProjectRepositories(UUID actorId, UUID projectId) {
        // 权限校验：必须是项目成员
        requireProjectMember(actorId, projectId);
        List<ProjectRepositoryEntity> bindings = projectRepositoryMapper.selectList(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                .eq(ProjectRepositoryEntity::getProjectId, projectId)
                .orderByDesc(ProjectRepositoryEntity::getBoundAt));

        if (bindings.isEmpty()) {
            return List.of();
        }

        List<UUID> repoIds = bindings.stream().map(ProjectRepositoryEntity::getRepositoryId).toList();
        java.util.Map<UUID, GitHubRepositoryEntity> repoMap = repositoryMapper.selectList(
                        new LambdaQueryWrapper<GitHubRepositoryEntity>().in(GitHubRepositoryEntity::getId, repoIds))
                .stream().collect(java.util.stream.Collectors.toMap(GitHubRepositoryEntity::getId, r -> r));

        return bindings.stream()
                .map(binding -> toProjectRepositoryResponse(binding, repoMap.get(binding.getRepositoryId())))
                .toList();
    }

    /**
     * 将团队层面已经授权的 GitHub 仓库，正式“绑定”到某个具体的项目中去使用。
     *
     * @param actorId   操作人（需要 Project Admin 权限）
     * @param projectId 项目 ID
     * @param request   绑定请求，包含仓库 ID 等
     */
    @Transactional
    public ProjectRepositoryResponse bindProjectRepository(UUID actorId, UUID projectId,
                                                           BindProjectRepositoryRequest request) {
        log.info("Binding GitHub repository (ID: {}) to projectId: {} by actorId: {}", request.getRepositoryId(),
                projectId, actorId);
        // 需要是项目管理员
        requireProjectAdmin(actorId, projectId);

        // 查找该团队激活状态的安装记录里，是否包含要绑定的这个仓库
        GitHubRepositoryEntity repository = findActiveRepositoryForProject(request.getInstallationId(),
                request.getRepositoryId(), projectId);
        // 不存在就抛异常
        if (repository == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPOSITORY_NOT_AUTHORIZED_FOR_PROJECT",
                    "Repository is not available through an active installation for this project team");
        }

        if (repository.getDefaultBranch() == null || repository.getDefaultBranch().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_REPOSITORY_METADATA_INCOMPLETE",
                    "Repository default branch is missing from metadata");
        }

        // 防止重复绑定：如果该仓库已经被当前项目绑定过，抛出冲突
        if (projectRepositoryMapper.selectOne(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                .eq(ProjectRepositoryEntity::getProjectId, projectId)
                .eq(ProjectRepositoryEntity::getRepositoryId, repository.getId())) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_REPOSITORY_ALREADY_BOUND",
                    "Repository is already bound to this project");
        }

        // 创建项目与仓库的绑定关系记录 (ProjectRepositoryEntity)
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(UUID.randomUUID());
        binding.setProjectId(projectId);
        binding.setRepositoryId(repository.getId());
        // 强制以后端 GitHub 的 defaultBranch 为准，忽略前端的覆盖值
        binding.setDefaultBranch(repository.getDefaultBranch());
        binding.setDisplayName(request.getDisplayName());
        binding.setBoundAt(LocalDateTime.now(clock));

        projectRepositoryMapper.insert(binding);
        return toProjectRepositoryResponse(binding, repository);
    }

    /**
     * 创建项目时批量绑定仓库（前端额外清单 §四）。
     * <p>
     * 传入 github_repositories.id（授权仓本地 UUID），逐个校验「属于该团队 ACTIVE 安装、
     * AUTHORIZED、未归档、有默认分支」后创建 project_repositories 绑定记录；
     * 等价于逐个调用绑定接口，但不要求 installationId（按仓库反查）。
     * 与创建项目同事务（由调用方 ProjectService.create 的事务传播）。
     */
    @Transactional
    public void bindRepositoriesOnCreate(UUID actorId, UUID teamId, UUID projectId,
                                         List<UUID> githubRepositoryIds) {
        if (githubRepositoryIds == null || githubRepositoryIds.isEmpty()) {
            return;
        }
        for (UUID repositoryId : new java.util.LinkedHashSet<>(githubRepositoryIds)) {
            GitHubRepositoryEntity repository = repositoryMapper.selectById(repositoryId);
            if (repository == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "GITHUB_REPOSITORY_NOT_FOUND",
                        "GitHub 授权仓库不存在");
            }
            GitHubInstallationEntity installation = installationMapper.selectById(repository.getInstallationId());
            if (installation == null || !teamId.equals(installation.getTeamId())
                    || !"ACTIVE".equals(installation.getStatus())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPOSITORY_NOT_AUTHORIZED_FOR_PROJECT",
                        "仓库不在该团队的有效安装授权范围内");
            }
            if (!"AUTHORIZED".equals(repository.getAuthorizationStatus()) || Boolean.TRUE.equals(repository.getArchived())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPOSITORY_NOT_AUTHORIZED_FOR_PROJECT",
                        "仓库未被授权或已归档");
            }
            if (repository.getDefaultBranch() == null || repository.getDefaultBranch().isBlank()) {
                throw new ApiException(HttpStatus.CONFLICT, "GITHUB_REPOSITORY_METADATA_INCOMPLETE",
                        "仓库默认分支缺失，无法绑定");
            }
            if (projectRepositoryMapper.selectOne(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                    .eq(ProjectRepositoryEntity::getProjectId, projectId)
                    .eq(ProjectRepositoryEntity::getRepositoryId, repository.getId())) != null) {
                throw new ApiException(HttpStatus.CONFLICT, "PROJECT_REPOSITORY_ALREADY_BOUND",
                        "仓库已被该项目绑定");
            }
            ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
            binding.setId(UUID.randomUUID());
            binding.setProjectId(projectId);
            binding.setRepositoryId(repository.getId());
            binding.setDefaultBranch(repository.getDefaultBranch());
            binding.setDisplayName(repository.getName());
            binding.setBoundAt(LocalDateTime.now(clock));
            projectRepositoryMapper.insert(binding);
        }
    }

    /**
     * 更新项目已绑定的 GitHub 仓库配置（如：默认分支、自定义显示名称）。
     * 只有 Project Admin 才能执行此操作。
     *
     * @param actorId             操作人的用户 ID
     * @param projectId           项目 ID
     * @param projectRepositoryId Qgents 内部的项目与仓库绑定关系 ID
     * @param request             包含需要更新的默认分支和显示名称的请求体
     * @return 更新后的项目绑定仓库详情响应
     */
    @Transactional
    public ProjectRepositoryResponse updateProjectRepository(UUID actorId, UUID projectId, UUID projectRepositoryId,
                                                             UpdateProjectRepositoryRequest request) {
        // 权限校验：必须是项目管理员
        requireProjectAdmin(actorId, projectId);
        // 根据绑定 ID 查找当前的绑定记录
        ProjectRepositoryEntity current = projectRepositoryMapper.selectById(projectRepositoryId);
        // 防越权：确保要更新的绑定关系确实属于请求的 projectId
        if (current == null || !projectId.equals(current.getProjectId())) {
            throw notFound("Project repository binding does not exist");
        }
        // 更新默认分支和自定义显示名称
        current.setDefaultBranch(request.getDefaultBranch());
        current.setDisplayName(request.getDisplayName());
        projectRepositoryMapper.updateById(current);

        GitHubRepositoryEntity repository = repositoryMapper.selectById(current.getRepositoryId());
        return toProjectRepositoryResponse(current, repository);
    }

    /**
     * 解除项目与某个 GitHub 仓库的绑定关系。
     * 若该绑定已被应用于流水线分支配置，则拒绝解绑（需先删除分支配置）。
     *
     * @param actorId             操作人的用户 ID
     * @param projectId           项目 ID
     * @param projectRepositoryId Qgents 内部的项目与仓库绑定关系 ID
     */
    @Transactional
    public void unbindProjectRepository(UUID actorId, UUID projectId, UUID projectRepositoryId) {
        // 权限校验：必须是项目管理员
        requireProjectAdmin(actorId, projectId);
        // 查找对应的绑定记录
        ProjectRepositoryEntity current = projectRepositoryMapper.selectById(projectRepositoryId);
        // 防越权校验
        if (current == null || !projectId.equals(current.getProjectId())) {
            throw notFound("Project repository binding does not exist");
        }
        // 如果这个仓库配置了分支，不给解绑
        if (branchConfigMapper.selectCount(new LambdaQueryWrapper<RepositoryBranchConfigEntity>()
                .eq(RepositoryBranchConfigEntity::getProjectRepositoryId, projectRepositoryId)) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_REPOSITORY_REFERENCED_BY_BRANCH_CONFIG",
                    "Delete branch configuration before unbinding this repository");
        }
        // 否则删除
        projectRepositoryMapper.deleteById(projectRepositoryId);
    }

    /**
     * 处理来自 GitHub App 的安装/授权回调请求。
     * 当用户在 GitHub 网页上点击“Install”或“Save”后，GitHub 会携带 state 调回这个接口。
     *
     * @param providerInstallationId GitHub 底层真实的 Installation ID
     * @param state                  授权时生成的加密状态，内含发起的团队 teamId
     */
    public UUID handleInstallationCallback(long providerInstallationId, String state) {
        log.info("Handling GitHub App installation callback. providerInstallationId: {}", providerInstallationId);
        Object callbackLock = INSTALLATION_CALLBACK_LOCKS.computeIfAbsent(providerInstallationId, ignored -> new Object());
        synchronized (callbackLock) {
            try {
                UUID teamId = gitHubClient.verifyInstallationState(state);
                syncInstallation(teamId, providerInstallationId);
                return teamId;
            } finally {
                INSTALLATION_CALLBACK_LOCKS.remove(providerInstallationId, callbackLock);
            }
        }
    }

    /**
     * Verifies state, synchronizes the installation, and preserves the initiating client for redirect routing.
     */
    public GitHubInstallationState handleInstallationCallbackDetails(long providerInstallationId, String state) {
        log.info("Handling GitHub App installation callback. providerInstallationId: {}", providerInstallationId);

        // 1. 验证 state 签名，并从中解析出真正发起授权的团队 ID
        Object callbackLock = INSTALLATION_CALLBACK_LOCKS.computeIfAbsent(providerInstallationId, ignored -> new Object());
        synchronized (callbackLock) {
            try {
                GitHubInstallationState callbackState = gitHubClient.verifyInstallationStateDetails(state);
                UUID teamId = callbackState.teamId();

                // 同一 GitHub 账号只能绑定一个团队：先做归属校验，冲突时不抛异常，由 Controller 重定向回前端展示明确提示
                String conflictCode = installationTeamConflict(providerInstallationId, teamId);
                if (conflictCode != null) {
                    callbackState = callbackState.withConflictCode(conflictCode);
                    return callbackState;
                }

                // Execute the core metadata synchronization while holding the per-installation lock.
                syncInstallation(teamId, providerInstallationId);

                return callbackState;
            } finally {
                INSTALLATION_CALLBACK_LOCKS.remove(providerInstallationId, callbackLock);
            }
        }
    }

    /**
     * 校验 installation 是否已被其他团队占用：返回 null 表示可绑定，否则返回冲突错误码。
     * 回调场景使用，避免冲突直接抛异常导致网关把 409 转成 502。
     */
    private String installationTeamConflict(long providerInstallationId, UUID teamId) {
        GitHubInstallationEntity existing = installationMapper.selectOne(
                new LambdaQueryWrapper<GitHubInstallationEntity>().eq(
                        GitHubInstallationEntity::getProviderInstallationId, providerInstallationId));
        if (existing != null && !existing.getTeamId().equals(teamId)) {
            return "GITHUB_INSTALLATION_TEAM_CONFLICT";
        }
        return null;
    }

    /**
     * 手动触发指定授权的全量同步。
     * 只有 Team Owner 才能执行此操作。
     */
    public GitHubInstallationResponse manualSyncInstallation(UUID actorId, UUID teamId, UUID installationId) {
        requireTeamOwner(actorId, teamId);
        GitHubInstallationEntity installationEntity = installationMapper.selectOne(
                new LambdaQueryWrapper<GitHubInstallationEntity>()
                        .eq(GitHubInstallationEntity::getId, installationId)
                        .eq(GitHubInstallationEntity::getTeamId, teamId));
        if (installationEntity == null) {
            throw notFound("GitHub installation does not exist");
        }
        return syncInstallation(teamId, installationEntity.getProviderInstallationId());
    }

    /**
     * 核心全量同步逻辑，提供给 callback 和手动刷新复用。
     * 远程 GitHub 查询在事务、行锁之外完成；落库阶段在事务内以 Installation 行锁复查本地状态，
     * 若安装已被 Webhook suspend/deleted 则不得用旧快照恢复 ACTIVE/AUTHORIZED。
     */
    private GitHubInstallationResponse syncInstallation(UUID teamId, long providerInstallationId) {
        // 锁外拉取 GitHub 快照：不持有数据库事务或行锁
        GitHubInstallationDetails installation = gitHubClient.getInstallation(providerInstallationId);
        List<GitHubRepositoryDetails> providerRepositories = gitHubClient.listRepositories(providerInstallationId);

        return required.execute(status -> syncInstallationInTransaction(teamId, providerInstallationId,
                installation, providerRepositories));
    }

    private GitHubInstallationResponse syncInstallationInTransaction(UUID teamId, long providerInstallationId,
                                                                    GitHubInstallationDetails installation,
                                                                    List<GitHubRepositoryDetails> providerRepositories) {
        // 行锁内读取并复查状态：与 Webhook 的 installation/suspend 事件按 Installation 串行
        GitHubInstallationEntity installationEntity = installationMapper.selectByProviderInstallationIdForUpdate(providerInstallationId);

        boolean newInstallation = installationEntity == null;

        if (!newInstallation && !installationEntity.getTeamId().equals(teamId)) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_INSTALLATION_TEAM_CONFLICT",
                    "This GitHub installation is already bound to another team");
        }
        // 已存在且被 Webhook suspend/deleted：不得用旧快照恢复 ACTIVE/AUTHORIZED，直接返回当前状态
        if (!newInstallation && !"ACTIVE".equals(installationEntity.getStatus())) {
            return toInstallationResponse(installationEntity);
        }

        if (newInstallation) {
            installationEntity = new GitHubInstallationEntity();
            installationEntity.setId(UUID.randomUUID());
            installationEntity.setTeamId(teamId);
        }

        installationEntity.setProviderInstallationId(installation.getInstallationId());
        installationEntity.setAccountLogin(installation.getAccountLogin());
        installationEntity.setAccountType(normalizeEnum(installation.getAccountType()));
        installationEntity.setStatus("ACTIVE");

        if (newInstallation) {
            installationMapper.insert(installationEntity);
        } else {
            installationMapper.updateById(installationEntity);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        List<Long> returnedProviderRepoIds = providerRepositories.stream()
                .map(GitHubRepositoryDetails::getRepositoryId).toList();

        // Keep all current-installation records for the later revocation pass.
        List<GitHubRepositoryEntity> existingInstallationRepos = repositoryMapper.selectList(
                new LambdaQueryWrapper<GitHubRepositoryEntity>()
                        .eq(GitHubRepositoryEntity::getInstallationId, installationEntity.getId()));
        Map<Long, GitHubRepositoryEntity> existingRepoMap = new HashMap<>();
        existingInstallationRepos.forEach(repository ->
                existingRepoMap.put(repository.getProviderRepositoryId(), repository));

        // A repository ID is globally unique in GitHub. Look it up globally so a reinstall for the
        // same team updates its existing mirror instead of violating uk_ghr_provider.
        if (!returnedProviderRepoIds.isEmpty()) {
            repositoryMapper.selectList(new LambdaQueryWrapper<GitHubRepositoryEntity>()
                            .in(GitHubRepositoryEntity::getProviderRepositoryId, returnedProviderRepoIds))
                    .forEach(repository -> existingRepoMap.put(repository.getProviderRepositoryId(), repository));
        }

        for (GitHubRepositoryDetails repository : providerRepositories) {
            GitHubRepositoryEntity repositoryEntity = existingRepoMap.get(repository.getRepositoryId());

            boolean newRepository = repositoryEntity == null;
            if (newRepository) {
                repositoryEntity = new GitHubRepositoryEntity();
                repositoryEntity.setId(UUID.randomUUID());
                repositoryEntity.setInstallationId(installationEntity.getId());
            } else if (!installationEntity.getId().equals(repositoryEntity.getInstallationId())) {
                GitHubInstallationEntity existingRepositoryInstallation = installationMapper
                        .selectById(repositoryEntity.getInstallationId());
                if (existingRepositoryInstallation == null
                        || !teamId.equals(existingRepositoryInstallation.getTeamId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "GITHUB_REPOSITORY_INSTALLATION_CONFLICT",
                            "This GitHub repository is already bound to another team installation");
                }
                // Keep the repository UUID so existing project repository bindings remain valid.
                repositoryEntity.setInstallationId(installationEntity.getId());
            }

            repositoryEntity.setProviderRepositoryId(repository.getRepositoryId());
            repositoryEntity.setOwnerLogin(repository.getOwnerLogin());
            repositoryEntity.setName(repository.getName());
            repositoryEntity.setDefaultBranch(repository.getDefaultBranch());
            repositoryEntity.setVisibility(normalizeEnum(repository.getVisibility()));
            repositoryEntity.setArchived(repository.isArchived());
            repositoryEntity.setAuthorizationStatus("AUTHORIZED");
            repositoryEntity.setSyncedAt(now);

            if (newRepository) {
                repositoryMapper.insert(repositoryEntity);
            } else {
                repositoryMapper.updateById(repositoryEntity);
            }
        }

        // 把不再返回的仓库标记为撤销授权
        if (!newInstallation) {
            for (GitHubRepositoryEntity existingRepo : existingInstallationRepos) {
                if ("AUTHORIZED".equals(existingRepo.getAuthorizationStatus()) &&
                        !returnedProviderRepoIds.contains(existingRepo.getProviderRepositoryId())) {
                    existingRepo.setAuthorizationStatus("REVOKED");
                    existingRepo.setSyncedAt(now);
                    repositoryMapper.updateById(existingRepo);
                }
            }
        }

        return toInstallationResponse(installationEntity);
    }

    private void requireTeamOwner(UUID actorId, UUID teamId) {
        if (!isTeamOwner(teamId, actorId)) {
            throw forbidden("Team owner access is required");
        }
    }

    private void requireProjectMember(UUID actorId, UUID projectId) {
        if (!hasProjectAccess(projectId, actorId)) {
            throw forbidden("Project member access is required");
        }
    }

    private void requireProjectAdmin(UUID actorId, UUID projectId) {
        if (!hasProjectAdminAccess(projectId, actorId)) {
            throw forbidden("Project admin access is required");
        }
    }

    private boolean hasProjectAccess(UUID projectId, UUID actorId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        return project != null && (isTeamOwner(project.getTeamId(), actorId)
                || projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMemberEntity>().eq(ProjectMemberEntity::getProjectId, projectId)
                        .eq(ProjectMemberEntity::getUserId, actorId)) > 0);
    }

    private boolean hasProjectAdminAccess(UUID projectId, UUID actorId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        return project != null && (isTeamOwner(project.getTeamId(), actorId)
                || projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMemberEntity>()
                .eq(ProjectMemberEntity::getProjectId, projectId).eq(ProjectMemberEntity::getUserId, actorId)
                .eq(ProjectMemberEntity::getRole, "PROJECT_ADMIN")) > 0);
    }

    private boolean isTeamOwner(UUID teamId, UUID actorId) {
        return teamMemberMapper
                .selectCount(new LambdaQueryWrapper<TeamMemberEntity>().eq(TeamMemberEntity::getTeamId, teamId)
                        .eq(TeamMemberEntity::getUserId, actorId).eq(TeamMemberEntity::getRole, "TEAM_OWNER")) > 0;
    }

    private boolean hasTeamRepositoryAccess(UUID teamId, UUID actorId) {
        if (isTeamOwner(teamId, actorId)) {
            return true;
        }
        List<UUID> projectIds = projectMapper.selectList(new LambdaQueryWrapper<ProjectEntity>()
                        .eq(ProjectEntity::getTeamId, teamId)
                        .select(ProjectEntity::getId))
                .stream().map(ProjectEntity::getId).toList();
        return projectIds.stream()
                .anyMatch(projectId -> projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMemberEntity>()
                        .eq(ProjectMemberEntity::getProjectId, projectId).eq(ProjectMemberEntity::getUserId, actorId)
                        .eq(ProjectMemberEntity::getRole, "PROJECT_ADMIN")) > 0);
    }

    private GitHubRepositoryEntity findActiveRepositoryForProject(UUID installationId, UUID repositoryId,
                                                                  UUID projectId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) {
            return null;
        }
        List<UUID> installationIds = activeInstallationIdsForTeam(project.getTeamId());
        return !installationIds.contains(installationId) ? null
                : repositoryMapper.selectOne(new LambdaQueryWrapper<GitHubRepositoryEntity>()
                .eq(GitHubRepositoryEntity::getId, repositoryId)
                .eq(GitHubRepositoryEntity::getInstallationId, installationId)
                .eq(GitHubRepositoryEntity::getArchived, false)
                .eq(GitHubRepositoryEntity::getAuthorizationStatus, "AUTHORIZED"));
    }

    private List<GitHubRepositoryEntity> findActiveRepositoriesByTeam(UUID teamId) {
        List<UUID> installationIds = activeInstallationIdsForTeam(teamId);
        return installationIds.isEmpty() ? List.of()
                : repositoryMapper.selectList(new LambdaQueryWrapper<GitHubRepositoryEntity>()
                .in(GitHubRepositoryEntity::getInstallationId, installationIds)
                // 团队授权仓库列表只返回仍可用的仓库：已撤权或已归档的不再暴露给前端供绑定
                .eq(GitHubRepositoryEntity::getAuthorizationStatus, "AUTHORIZED")
                .eq(GitHubRepositoryEntity::getArchived, false)
                .orderByAsc(GitHubRepositoryEntity::getOwnerLogin, GitHubRepositoryEntity::getName));
    }

    private List<UUID> activeInstallationIdsForTeam(UUID teamId) {
        return installationMapper.selectList(new LambdaQueryWrapper<GitHubInstallationEntity>()
                        .eq(GitHubInstallationEntity::getTeamId, teamId)
                        .eq(GitHubInstallationEntity::getStatus, "ACTIVE")
                        .select(GitHubInstallationEntity::getId))
                .stream().map(GitHubInstallationEntity::getId).toList();
    }

    private GitHubInstallationResponse toInstallationResponse(GitHubInstallationEntity installation) {
        return new GitHubInstallationResponse(installation.getId(), installation.getProviderInstallationId(),
                installation.getAccountLogin(), installation.getAccountType(), installation.getStatus(),
                installation.getCreatedAt(), installation.getUpdatedAt());
    }

    private GitHubRepositoryResponse toRepositoryResponse(GitHubRepositoryEntity repository) {
        String fullName = repository.getOwnerLogin() + "/" + repository.getName();
        String githubUrl = org.springframework.web.util.UriComponentsBuilder.newInstance()
                .scheme("https").host("github.com").pathSegment(repository.getOwnerLogin(), repository.getName())
                .build().toUriString();
        return new GitHubRepositoryResponse(repository.getId(), repository.getInstallationId(),
                repository.getProviderRepositoryId(), fullName, githubUrl,
                repository.getDefaultBranch(), repository.getVisibility(),
                Boolean.TRUE.equals(repository.getArchived()), repository.getAuthorizationStatus(),
                repository.getSyncedAt());
    }

    private ProjectRepositoryResponse toProjectRepositoryResponse(ProjectRepositoryEntity binding, GitHubRepositoryEntity repository) {
        String fullName = repository.getOwnerLogin() + "/" + repository.getName();
        String githubUrl = org.springframework.web.util.UriComponentsBuilder.newInstance()
                .scheme("https").host("github.com").pathSegment(repository.getOwnerLogin(), repository.getName())
                .build().toUriString();
        return new ProjectRepositoryResponse(binding.getId(), binding.getRepositoryId(),
                repository.getInstallationId(), repository.getProviderRepositoryId(),
                fullName, githubUrl, binding.getDefaultBranch(), binding.getDisplayName(),
                repository.getAuthorizationStatus(), repository.getSyncedAt(), binding.getBoundAt());
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizeEnum(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "GITHUB_REPOSITORY_ACCESS_DENIED", message);
    }

    private ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "GITHUB_RESOURCE_NOT_FOUND", message);
    }
}
