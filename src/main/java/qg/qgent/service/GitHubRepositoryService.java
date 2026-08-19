package qg.qgent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
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
    private final TaskMapper taskMapper;
    private final GitHubAppClient gitHubClient;
    private final Clock clock;
    private final TransactionTemplate required;

    public GitHubRepositoryService(GitHubInstallationMapper installationMapper, GitHubRepositoryMapper repositoryMapper,
                                   ProjectRepositoryMapper projectRepositoryMapper,
                                   ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper,
                                   TeamMemberMapper teamMemberMapper, TaskMapper taskMapper,
                                   GitHubAppClient gitHubClient, Clock clock,
                                   PlatformTransactionManager transactionManager) {
        this.installationMapper = installationMapper;
        this.repositoryMapper = repositoryMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.taskMapper = taskMapper;
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
     * 解除团队已安装的 GitHub App 授权记录（解除 Qgents 团队关联，不是替用户去 GitHub 远程卸载 App）。
     * 若该安装下的仓库仍被项目绑定引用，则拒绝解除并返回 409 GITHUB_INSTALLATION_IN_USE，不删除任何数据；
     * 若没有项目绑定，先显式删除该安装下未绑定的仓库镜像，再删除安装记录，避免外键约束触发 500。
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

        // 查询该安装记录下同步过来的所有仓库镜像
        List<GitHubRepositoryEntity> repositories = repositoryMapper.selectList(new LambdaQueryWrapper<GitHubRepositoryEntity>()
                .eq(GitHubRepositoryEntity::getInstallationId, installationId));
        List<UUID> repositoryIds = repositories.stream().map(GitHubRepositoryEntity::getId).toList();

        // 若任一仓库仍被项目绑定引用，拒绝解除，不删除任何数据
        if (!repositoryIds.isEmpty() && projectRepositoryMapper.selectCount(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                .in(ProjectRepositoryEntity::getRepositoryId, repositoryIds)) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_INSTALLATION_IN_USE",
                    "Unbind project repositories before removing this GitHub installation");
        }

        // 无项目绑定：先显式删除未绑定的仓库镜像（避免外键约束），再删除安装记录
        if (!repositoryIds.isEmpty()) {
            repositoryMapper.delete(new LambdaQueryWrapper<GitHubRepositoryEntity>()
                    .eq(GitHubRepositoryEntity::getInstallationId, installationId));
        }
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
                .eq(ProjectRepositoryEntity::getStatus, "ACTIVE")
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
     * 查询项目绑定仓库的真实 GitHub 远程分支。工作分支（Task/Workspace）由另一个接口负责，
     * 本接口只返回 GitHub 上确实存在的 refs/heads/*。
     */
    public List<RemoteBranchResponse> listRemoteBranches(UUID actorId, UUID projectId,
                                                         UUID projectRepositoryId) {
        BranchContext context = requireBranchContext(actorId, projectId, projectRepositoryId, false);
        List<GitHubBranchDetails> branches = gitHubClient.listBranches(
                context.installation().getProviderInstallationId(), context.repository().getOwnerLogin(),
                context.repository().getName());
        if (branches == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_API_UNAVAILABLE",
                    "GitHub 未返回有效的分支列表");
        }
        for (GitHubBranchDetails branch : branches) {
            if (branch == null || branch.name() == null || branch.name().isBlank()
                    || branch.commitSha() == null || branch.commitSha().isBlank()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_API_UNAVAILABLE",
                        "GitHub 返回的分支数据不完整");
            }
        }
        return branches.stream()
                .sorted(java.util.Comparator.comparing(GitHubBranchDetails::name))
                .map(branch -> new RemoteBranchResponse(branch.name(), branch.commitSha(),
                        branch.name().equals(context.repository().getDefaultBranch()),
                        branch.name().equals(context.binding().getDefaultBranch())))
                .toList();
    }

    /**
     * 从已有远程分支创建 GitHub 远程分支。来源引用先由 GitHub 解析为 SHA，
     * 再调用受控 GitHub App 客户端创建 refs/heads，不接受客户端直接提交 SHA。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RemoteBranchResponse createRemoteBranch(UUID actorId, UUID projectId, UUID projectRepositoryId,
                                                   CreateRemoteBranchRequest request) {
        BranchContext context = requireBranchContext(actorId, projectId, projectRepositoryId, true);
        String branchName = normalizeBranchName(request.getName());
        String fromRef = normalizeBranchName(request.getFromRef());
        if (branchName.equals(fromRef)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_BRANCH_NAME", "新分支不能与来源分支相同");
        }
        long installationId = context.installation().getProviderInstallationId();
        GitHubBranchDetails source = gitHubClient.getBranch(installationId, context.repository().getOwnerLogin(),
                context.repository().getName(), fromRef);
        if (source == null || source.commitSha() == null || source.commitSha().isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GIT_BRANCH_NOT_FOUND", "来源分支不存在");
        }
        String owner = context.repository().getOwnerLogin();
        String repositoryName = context.repository().getName();
        GitHubBranchDetails existing = findExistingBranch(gitHubClient, installationId, owner, repositoryName,
                branchName);
        if (existing != null) {
            return existingBranchResponse(context, branchName, source.commitSha(), existing);
        }
        GitHubBranchDetails created;
        try {
            created = gitHubClient.createBranch(installationId, owner, repositoryName, branchName, source.commitSha());
        } catch (ApiException exception) {
            // GitHub 已完成创建但响应丢失时，重试会返回 422/409。重新读取目标引用恢复幂等结果。
            if (!"GIT_BRANCH_ALREADY_EXISTS".equals(exception.code())) {
                throw exception;
            }
            GitHubBranchDetails recovered = findExistingBranch(gitHubClient, installationId, owner, repositoryName,
                    branchName);
            if (recovered != null) {
                return existingBranchResponse(context, branchName, source.commitSha(), recovered);
            }
            throw exception;
        }
        if (created == null || created.name() == null || created.commitSha() == null || created.commitSha().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_API_UNAVAILABLE", "GitHub 未返回有效的分支创建结果");
        }
        if (!branchName.equals(created.name())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_API_UNAVAILABLE", "GitHub 返回的分支名与请求不一致");
        }
        return new RemoteBranchResponse(created.name(), created.commitSha(),
                created.name().equals(context.repository().getDefaultBranch()),
                created.name().equals(context.binding().getDefaultBranch()));
    }

    /**
     * 在团队 GitHub App 账号下新建一个仓库（事务外），返回建仓结果与所用安装记录，不落库。
     * 采用 NOT_SUPPORTED 挂起调用方事务，确保 GitHub 建仓 HTTP 不持有数据库事务或行锁（AGENTS §3.4）。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RemoteRepositoryCreation createRemoteRepository(UUID actorId, UUID teamId, NewProjectRepositoryRequest request) {
        requireTeamOwner(actorId, teamId);
        GitHubInstallationEntity installation = resolveInstallationForCreate(teamId, request.getInstallationId());
        GitHubRepositoryDetails created = gitHubClient.createRepository(
                installation.getProviderInstallationId(), installation.getAccountType(), installation.getAccountLogin(),
                new GitHubRepositoryCreateRequest(request.getName(), request.getDescription(),
                        request.getIsPrivate() == null || request.getIsPrivate(), true));
        return new RemoteRepositoryCreation(installation, created);
    }

    /**
     * 项目本地事务回滚时，补偿删除本次刚创建的远端仓库。该操作不持有数据库事务或行锁。
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void deleteRemoteRepository(RemoteRepositoryCreation creation) {
        GitHubInstallationEntity installation = creation.installation();
        GitHubRepositoryDetails repository = creation.repository();
        gitHubClient.deleteRepository(installation.getProviderInstallationId(), repository.getOwnerLogin(),
                repository.getName());
    }

    /**
     * 在建仓成功后、调用方事务内落库：写入仓库镜像并绑定到项目。不自行开启事务，
     * 由调用方（项目创建事务）保证与项目落库的原子性。
     */
    public ProjectRepositoryResponse bindCreatedRepository(UUID projectId, RemoteRepositoryCreation creation,
                                                           NewProjectRepositoryRequest request) {
        GitHubInstallationEntity installation = creation.installation();
        GitHubRepositoryDetails created = creation.repository();
        GitHubRepositoryEntity mirror = repositoryMapper.selectOne(new LambdaQueryWrapper<GitHubRepositoryEntity>()
                .eq(GitHubRepositoryEntity::getProviderRepositoryId, created.getRepositoryId())
                .eq(GitHubRepositoryEntity::getInstallationId, installation.getId()));
        if (mirror == null) {
            mirror = new GitHubRepositoryEntity();
            mirror.setId(UuidV7.next());
            mirror.setInstallationId(installation.getId());
            mirror.setProviderRepositoryId(created.getRepositoryId());
            mirror.setOwnerLogin(created.getOwnerLogin());
            mirror.setName(created.getName());
            mirror.setDefaultBranch(created.getDefaultBranch());
            mirror.setVisibility(normalizeEnum(created.getVisibility()));
            mirror.setArchived(created.isArchived());
            mirror.setAuthorizationStatus("AUTHORIZED");
            mirror.setSyncedAt(LocalDateTime.now(clock));
            repositoryMapper.insert(mirror);
        }
        String displayName = request.getDisplayName() == null || request.getDisplayName().isBlank()
                ? created.getName() : request.getDisplayName();
        ProjectRepositoryEntity binding = upsertProjectBinding(projectId, mirror, displayName);
        return toProjectRepositoryResponse(binding, mirror);
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

        // 绑定或恢复：ACTIVE 冲突，UNBOUND 恢复复用原 id，否则新建（强制以后端 defaultBranch 为准）
        ProjectRepositoryEntity binding = upsertProjectBinding(projectId, repository, request.getDisplayName());
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
            upsertProjectBinding(projectId, repository, repository.getName());
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
    public ProjectRepositoryResponse updateProjectRepository(UUID actorId, UUID projectId, UUID projectRepositoryId,
                                                              UpdateProjectRepositoryRequest request) {
        BranchContext context = requireBranchContext(actorId, projectId, projectRepositoryId, true);
        String defaultBranch = normalizeBranchName(request.getDefaultBranch());
        // GitHub 远程校验必须发生在事务外，避免外部 HTTP 调用持有数据库锁。
        gitHubClient.getBranch(context.installation().getProviderInstallationId(),
                context.repository().getOwnerLogin(), context.repository().getName(), defaultBranch);

        return required.execute(status -> {
            ProjectRepositoryEntity current = projectRepositoryMapper.selectByIdForUpdate(projectRepositoryId);
            if (current == null || !projectId.equals(current.getProjectId())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_REPOSITORY_NOT_FOUND",
                        "Project repository binding does not exist");
            }
            if (!"ACTIVE".equals(current.getStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "PROJECT_REPOSITORY_UNBOUND",
                        "Project repository binding is unbound");
            }
            current.setDefaultBranch(defaultBranch);
            current.setDisplayName(request.getDisplayName());
            projectRepositoryMapper.updateById(current);
            return toProjectRepositoryResponse(current, context.repository());
        });
    }

    /**
     * 软解绑项目仓库：不物理删除绑定记录，仅标记 UNBOUND 并写入 unbound_at，
     * 保留 RequirementGroup / Task / Workspace / Diff / MR / 分支配置等历史外键引用。
     * <p>
     * 幂等：已 UNBOUND 的绑定重复解绑直接返回，不报错。
     * 活动 Task 占用校验：该仓库仍被 PLANNING/RUNNING 等进行中任务使用时返回
     * 409 PROJECT_REPOSITORY_IN_USE，不改变状态。
     *
     * @param actorId             操作人的用户 ID
     * @param projectId           项目 ID
     * @param projectRepositoryId Qgents 内部的项目与仓库绑定关系 ID
     */
    @Transactional
    public void unbindProjectRepository(UUID actorId, UUID projectId, UUID projectRepositoryId) {
        // 权限校验：必须是项目管理员
        requireProjectAdmin(actorId, projectId);
        // 锁定父记录，避免并发下重复软解绑或与重新绑定竞争
        ProjectRepositoryEntity current = projectRepositoryMapper.selectByIdForUpdate(projectRepositoryId);
        // 防越权校验
        if (current == null || !projectId.equals(current.getProjectId())) {
            throw notFound("Project repository binding does not exist");
        }
        // 幂等：已软解绑的绑定重复解绑直接返回
        if ("UNBOUND".equals(current.getStatus())) {
            return;
        }
        // 活动任务占用校验：有 PLANNING/RUNNING 等进行中任务使用该仓库时拒绝软解绑
        if (taskMapper.countActiveTasksUsingRepository(projectRepositoryId) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_REPOSITORY_IN_USE",
                    "Repository is used by an active task and cannot be unbound");
        }
        // 软解绑：标记 UNBOUND，保留历史记录与下游外键引用
        current.setStatus("UNBOUND");
        current.setUnboundAt(LocalDateTime.now(clock));
        projectRepositoryMapper.updateById(current);
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

                // Callback conflicts must redirect to the initiating client instead of exposing a 409 to GitHub.
                try {
                    syncInstallation(teamId, providerInstallationId);
                } catch (ApiException exception) {
                    if ("GITHUB_INSTALLATION_TEAM_CONFLICT".equals(exception.code())) {
                        return callbackState.withConflictCode("GITHUB_INSTALLATION_TEAM_CONFLICT");
                    }
                    throw exception;
                }

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
                    throw new ApiException(HttpStatus.CONFLICT, "GITHUB_INSTALLATION_TEAM_CONFLICT",
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

    private GitHubBranchDetails findExistingBranch(GitHubAppClient client, long installationId, String owner,
                                                    String repository, String branchName) {
        try {
            return client.getBranch(installationId, owner, repository, branchName);
        } catch (ApiException exception) {
            if ("GIT_BRANCH_NOT_FOUND".equals(exception.code())) {
                return null;
            }
            throw exception;
        }
    }

    private RemoteBranchResponse existingBranchResponse(BranchContext context, String branchName, String sourceSha,
                                                        GitHubBranchDetails existing) {
        if (existing.commitSha() == null || !sourceSha.equalsIgnoreCase(existing.commitSha())) {
            throw new ApiException(HttpStatus.CONFLICT, "GIT_BRANCH_ALREADY_EXISTS",
                    "目标远程分支已存在且来源提交不同");
        }
        return new RemoteBranchResponse(branchName, existing.commitSha(),
                branchName.equals(context.repository().getDefaultBranch()),
                branchName.equals(context.binding().getDefaultBranch()));
    }

    private BranchContext requireBranchContext(UUID actorId, UUID projectId, UUID projectRepositoryId,
                                                boolean adminRequired) {
        if (adminRequired) {
            requireProjectAdmin(actorId, projectId);
        } else {
            requireProjectMember(actorId, projectId);
        }
        ProjectRepositoryEntity binding = projectRepositoryMapper.selectById(projectRepositoryId);
        if (binding == null || !projectId.equals(binding.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_REPOSITORY_NOT_FOUND",
                    "Project repository binding does not exist");
        }
        if (!"ACTIVE".equals(binding.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_REPOSITORY_UNBOUND",
                    "Project repository binding is unbound");
        }
        GitHubRepositoryEntity repository = repositoryMapper.selectById(binding.getRepositoryId());
        if (repository == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GITHUB_REPOSITORY_NOT_FOUND",
                    "GitHub repository does not exist");
        }
        if (!"AUTHORIZED".equals(repository.getAuthorizationStatus()) || Boolean.TRUE.equals(repository.getArchived())) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_REPOSITORY_UNAVAILABLE",
                    "GitHub repository is not available");
        }
        GitHubInstallationEntity installation = installationMapper.selectById(repository.getInstallationId());
        if (installation == null || !"ACTIVE".equals(installation.getStatus())
                || installation.getProviderInstallationId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_INSTALLATION_UNAVAILABLE",
                    "GitHub App installation is not active");
        }
        return new BranchContext(binding, repository, installation);
    }

    /**
     * 统一约束分支引用格式，拒绝 GitHub API 的 refs/heads 前缀和危险 ref 语法。
     */
    private String normalizeBranchName(String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BRANCH_NAME", "分支名不能为空");
        }
        String branch = value.trim();
        if (branch.length() > 255 || !branch.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}")
                || branch.startsWith("/") || branch.startsWith("refs/") || branch.contains("//")
                || branch.contains("..") || branch.contains("@{") || branch.endsWith("/")
                || branch.endsWith(".") || branch.endsWith(".lock") || branch.contains("\\")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BRANCH_NAME", "分支名格式不合法");
        }
        return branch;
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

    /**
     * 绑定或恢复项目仓库绑定：同项目同仓库已有 ACTIVE 绑定则冲突；已有 UNBOUND 则恢复为
     * ACTIVE 并复用原 id（保留历史外键引用）；否则新建 ACTIVE 绑定。返回落库后的绑定记录。
     * <p>
     * 查询持行锁（FOR UPDATE）串行化同键上的软解绑与并发绑定，避免竞态或重复插入。
     */
    private ProjectRepositoryEntity upsertProjectBinding(UUID projectId, GitHubRepositoryEntity repository,
                                                          String displayName) {
        ProjectRepositoryEntity existing = projectRepositoryMapper
                .selectByProjectAndRepositoryForUpdate(projectId, repository.getId());
        if (existing != null) {
            if ("ACTIVE".equals(existing.getStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "PROJECT_REPOSITORY_ALREADY_BOUND",
                        "仓库已被该项目绑定");
            }
            existing.setStatus("ACTIVE");
            existing.setDefaultBranch(repository.getDefaultBranch());
            existing.setDisplayName(displayName);
            existing.setBoundAt(LocalDateTime.now(clock));
            projectRepositoryMapper.update(existing,
                    Wrappers.<ProjectRepositoryEntity>lambdaUpdate()
                            .eq(ProjectRepositoryEntity::getId, existing.getId())
                            // 显式置空：updateById 会忽略 null 字段，导致 unbound_at 残留旧值
                            .set(ProjectRepositoryEntity::getUnboundAt, null)
                            .set(ProjectRepositoryEntity::getStatus, "ACTIVE")
                            .set(ProjectRepositoryEntity::getDefaultBranch, existing.getDefaultBranch())
                            .set(ProjectRepositoryEntity::getDisplayName, existing.getDisplayName())
                            .set(ProjectRepositoryEntity::getBoundAt, existing.getBoundAt()));
            existing.setUnboundAt(null);
            return existing;
        }
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(UuidV7.next());
        binding.setProjectId(projectId);
        binding.setRepositoryId(repository.getId());
        binding.setDefaultBranch(repository.getDefaultBranch());
        binding.setDisplayName(displayName);
        binding.setBoundAt(LocalDateTime.now(clock));
        binding.setStatus("ACTIVE");
        projectRepositoryMapper.insert(binding);
        return binding;
    }

    /**
     * 解析自动建仓所用的安装记录：指定 installationId 时校验其归属与状态；
     * 未指定时要求团队恰好一个 ACTIVE 安装，否则明确报错要求指定。
     */
    private GitHubInstallationEntity resolveInstallationForCreate(UUID teamId, UUID installationId) {
        if (installationId != null) {
            GitHubInstallationEntity installation = installationMapper.selectById(installationId);
            if (installation == null || !teamId.equals(installation.getTeamId())
                    || !"ACTIVE".equalsIgnoreCase(installation.getStatus())
                    || installation.getProviderInstallationId() == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GITHUB_INSTALLATION_NOT_ACTIVE",
                        "GitHub App installation is not active for this team");
            }
            return installation;
        }
        List<GitHubInstallationEntity> active = installationMapper.selectList(
                new LambdaQueryWrapper<GitHubInstallationEntity>()
                        .eq(GitHubInstallationEntity::getTeamId, teamId)
                        .eq(GitHubInstallationEntity::getStatus, "ACTIVE"));
        if (active.size() == 1 && active.get(0).getProviderInstallationId() != null) {
            return active.get(0);
        }
        if (active.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GITHUB_INSTALLATION_NOT_ACTIVE",
                    "团队没有可用的 GitHub App 安装授权");
        }
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GITHUB_INSTALLATION_REQUIRED",
                "团队有多个 GitHub App 安装授权，请指定 installationId");
    }

    /**
     * 自动建仓的事务外结果：所用安装记录 + 新建仓库元数据。
     */
    public record RemoteRepositoryCreation(GitHubInstallationEntity installation, GitHubRepositoryDetails repository) {
    }

    private record BranchContext(ProjectRepositoryEntity binding, GitHubRepositoryEntity repository,
                                 GitHubInstallationEntity installation) {
    }
}
