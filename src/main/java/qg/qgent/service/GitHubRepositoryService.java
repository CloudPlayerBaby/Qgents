package qg.qgent.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import qg.qgent.api.ApiException;
import qg.qgent.dto.BindProjectRepositoryRequest;
import qg.qgent.dto.GitHubInstallationResponse;
import qg.qgent.dto.GitHubInstallationUrlResponse;
import qg.qgent.dto.GitHubRepositoryResponse;
import qg.qgent.dto.ProjectRepositoryResponse;
import qg.qgent.dto.UpdateProjectRepositoryRequest;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RepositoryBranchConfigEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubInstallationDetails;
import qg.qgent.github.GitHubRepositoryDetails;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.RepositoryBranchConfigMapper;
import qg.qgent.mapper.TeamMemberMapper;

/**
 * GitHub 仓库与团队/项目授权绑定服务。
 * 负责处理 GitHub App 的安装、卸载、仓库同步，以及仓库与具体 Qgents 项目的绑定与解绑逻辑。
 */
@Service
@Slf4j
public class GitHubRepositoryService {
    private final GitHubInstallationMapper installationMapper;
    private final GitHubRepositoryMapper repositoryMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final RepositoryBranchConfigMapper branchConfigMapper;
    private final GitHubAppClient gitHubClient;
    private final Clock clock;

    public GitHubRepositoryService(GitHubInstallationMapper installationMapper, GitHubRepositoryMapper repositoryMapper,
            ProjectRepositoryMapper projectRepositoryMapper,
            ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper,
            TeamMemberMapper teamMemberMapper, RepositoryBranchConfigMapper branchConfigMapper,
            GitHubAppClient gitHubClient, Clock clock) {
        this.installationMapper = installationMapper;
        this.repositoryMapper = repositoryMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.branchConfigMapper = branchConfigMapper;
        this.gitHubClient = gitHubClient;
        this.clock = clock;
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
        // 需要是团队所有者
        requireTeamOwner(actorId, teamId);
        // 调用底层 GitHub SDK 生成包含状态（加密的 teamId）的安装链接，时效为 10 分钟
        return new GitHubInstallationUrlResponse(gitHubClient.createInstallationUrl(teamId, actorId),
                LocalDateTime.now(clock).plusSeconds(600));
    }

    /**
     * 列出团队安装的GitHub仓库
     * 
     * @param actorId 操作人ID
     * @param teamId  团队ID
     * @return 安装响应体列表
     */
    public List<GitHubInstallationResponse> listInstallations(UUID actorId, UUID teamId) {
        // 需要是团队所有者
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
     * 列出团队安装Github仓库的记录
     * 
     * @param actorId 操作人ID
     * @param teamId  团队ID
     * @return 仓库响应体列表
     */
    public List<GitHubRepositoryResponse> listTeamRepositories(UUID actorId, UUID teamId) {
        if (!hasTeamRepositoryAccess(teamId, actorId)) {
            throw forbidden("Team owner or project admin access is required");
        }
        return findActiveRepositoriesByTeam(teamId).stream().map(this::toRepositoryResponse).toList();
    }

    /**
     * 列出项目绑定的Github仓库记录
     * 
     * @param actorId
     * @param projectId
     * @return
     */
    public List<ProjectRepositoryResponse> listProjectRepositories(UUID actorId, UUID projectId) {
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
     * 更新项目仓库
     * 
     * @param actorId             操作人ID
     * @param projectId           项目ID
     * @param projectRepositoryId 项目仓库ID
     * @param request             更新请求
     * @return
     */
    @Transactional
    public ProjectRepositoryResponse updateProjectRepository(UUID actorId, UUID projectId, UUID projectRepositoryId,
            UpdateProjectRepositoryRequest request) {
        // 需要是项目管理员
        requireProjectAdmin(actorId, projectId);
        // 找到对应的记录
        ProjectRepositoryEntity current = projectRepositoryMapper.selectById(projectRepositoryId);
        // 不存在或者不合法
        if (current == null || !projectId.equals(current.getProjectId())) {
            throw notFound("Project repository binding does not exist");
        }
        // 更新默认分支和显示名称
        current.setDefaultBranch(request.getDefaultBranch());
        current.setDisplayName(request.getDisplayName());
        projectRepositoryMapper.updateById(current);
        
        GitHubRepositoryEntity repository = repositoryMapper.selectById(current.getRepositoryId());
        return toProjectRepositoryResponse(current, repository);
    }

    /**
     * 解绑项目仓库
     * 
     * @param actorId             操作人ID
     * @param projectId           项目ID
     * @param projectRepositoryId 项目仓库ID
     */
    @Transactional
    public void unbindProjectRepository(UUID actorId, UUID projectId, UUID projectRepositoryId) {
        // 需要是项目管理员
        requireProjectAdmin(actorId, projectId);
        // 找到对应的记录
        ProjectRepositoryEntity current = projectRepositoryMapper.selectById(projectRepositoryId);
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
    @Transactional
    public UUID handleInstallationCallback(long providerInstallationId, String state) {
        log.info("Handling GitHub App installation callback. providerInstallationId: {}", providerInstallationId);
        
        // 1. 验证 state 签名，并从中解析出真正发起授权的团队 ID
        UUID teamId = gitHubClient.verifyInstallationState(state);
        
        // 执行核心全量同步逻辑
        syncInstallation(teamId, providerInstallationId);
        
        return teamId;
    }

    /**
     * 手动触发指定授权的全量同步。
     * 只有 Team Owner 才能执行此操作。
     */
    @Transactional
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
     * 核心全量同步逻辑，提供给 callback 和手动刷新复用
     */
    private GitHubInstallationResponse syncInstallation(UUID teamId, long providerInstallationId) {
        // 先读取 GitHub Installation 详情和完整 Repository 集合
        GitHubInstallationDetails installation = gitHubClient.getInstallation(providerInstallationId);
        List<GitHubRepositoryDetails> providerRepositories = gitHubClient.listRepositories(providerInstallationId);
        
        // 在本地库查找这条安装记录
        GitHubInstallationEntity installationEntity = installationMapper.selectOne(
                new LambdaQueryWrapper<GitHubInstallationEntity>().eq(
                        GitHubInstallationEntity::getProviderInstallationId, providerInstallationId));
                        
        boolean newInstallation = installationEntity == null;
        
        if (!newInstallation && !installationEntity.getTeamId().equals(teamId)) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_INSTALLATION_TEAM_CONFLICT",
                    "This GitHub installation is already bound to another team");
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
                
        // 批量查询本地已存在的仓库避免 N+1
        List<GitHubRepositoryEntity> existingRepos = newInstallation ? List.of() :
                repositoryMapper.selectList(new LambdaQueryWrapper<GitHubRepositoryEntity>()
                        .eq(GitHubRepositoryEntity::getInstallationId, installationEntity.getId()));
                        
        java.util.Map<Long, GitHubRepositoryEntity> existingRepoMap = existingRepos.stream()
                .collect(java.util.stream.Collectors.toMap(GitHubRepositoryEntity::getProviderRepositoryId, r -> r));
                
        for (GitHubRepositoryDetails repository : providerRepositories) {
            GitHubRepositoryEntity repositoryEntity = existingRepoMap.get(repository.getRepositoryId());
                            
            boolean newRepository = repositoryEntity == null;
            if (newRepository) {
                repositoryEntity = new GitHubRepositoryEntity();
                repositoryEntity.setId(UUID.randomUUID());
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
            for (GitHubRepositoryEntity existingRepo : existingRepos) {
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
