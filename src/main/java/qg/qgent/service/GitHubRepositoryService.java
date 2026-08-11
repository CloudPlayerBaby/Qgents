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
     * 创建GitHub安装URL
     * 
     * @param actorId 操作人ID
     * @param teamId  团队ID
     * @return 安装URL响应体
     */
    public GitHubInstallationUrlResponse createInstallationUrl(UUID actorId, UUID teamId) {
        log.info("Generating GitHub installation URL for teamId: {}, requested by actorId: {}", teamId, actorId);
        // 需要是团队所有者
        requireTeamOwner(actorId, teamId);
        // 返回一个安装URL，并设置过期时间为10分钟
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
     * 移除安装
     * 
     * @param actorId        操作人ID
     * @param teamId         团队ID
     * @param installationId 安装ID
     */
    @Transactional
    public void removeInstallation(UUID actorId, UUID teamId, UUID installationId) {
        // 需要是团队所有者
        requireTeamOwner(actorId, teamId);
        // 找到对应的安装实体
        GitHubInstallationEntity installation = installationMapper
                .selectOne(new LambdaQueryWrapper<GitHubInstallationEntity>()
                        .eq(GitHubInstallationEntity::getId, installationId)
                        .eq(GitHubInstallationEntity::getTeamId, teamId));
        if (installation == null) {
            throw notFound("GitHub installation does not exist");
        }

        // 获取到一个安装的所有仓库ID列表
        List<UUID> repositoryIds = repositoryMapper.selectList(new LambdaQueryWrapper<GitHubRepositoryEntity>()
                .eq(GitHubRepositoryEntity::getInstallationId, installationId)
                .select(GitHubRepositoryEntity::getId))
                .stream().map(GitHubRepositoryEntity::getId).toList();

        // 如果IDS不为空，并且有项目仓库绑定到这个安装
        if (!repositoryIds.isEmpty()
                && projectRepositoryMapper.selectCount(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                        .in(ProjectRepositoryEntity::getRepositoryId, repositoryIds)) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_INSTALLATION_IN_USE",
                    "Unbind project repositories before removing this GitHub installation");
        }
        // 否则删除安装
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
        return projectRepositoryMapper.selectList(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                .eq(ProjectRepositoryEntity::getProjectId, projectId)
                .orderByDesc(ProjectRepositoryEntity::getBoundAt))
                .stream().map(this::toProjectRepositoryResponse).toList();
    }

    /**
     * 绑定项目仓库
     * 
     * @param actorId   操作人ID
     * @param projectId 项目ID
     * @param request
     * @return
     */
    @Transactional
    public ProjectRepositoryResponse bindProjectRepository(UUID actorId, UUID projectId,
            BindProjectRepositoryRequest request) {
        log.info("Binding GitHub repository (ID: {}) to projectId: {} by actorId: {}", request.getRepositoryId(),
                projectId, actorId);
        // 需要是项目管理员
        requireProjectAdmin(actorId, projectId);
        // 找到对应的仓库实体
        GitHubRepositoryEntity repository = findActiveRepositoryForProject(request.getInstallationId(),
                request.getRepositoryId(), projectId);
        // 不存在就抛异常
        if (repository == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPOSITORY_NOT_AUTHORIZED_FOR_PROJECT",
                    "Repository is not available through an active installation for this project team");
        }
        // 如果这个仓库已经绑定到这个项目了，抛异常
        if (projectRepositoryMapper.selectOne(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                .eq(ProjectRepositoryEntity::getProjectId, projectId)
                .eq(ProjectRepositoryEntity::getRepositoryId, repository.getId())) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_REPOSITORY_ALREADY_BOUND",
                    "Repository is already bound to this project");
        }

        // 否则创建绑定记录
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(UUID.randomUUID());
        binding.setProjectId(projectId);
        binding.setRepositoryId(repository.getId());
        binding.setDefaultBranch(blankToDefault(request.getDefaultBranch(), repository.getDefaultBranch()));
        binding.setDisplayName(request.getDisplayName());
        binding.setBoundAt(LocalDateTime.now(clock));
        projectRepositoryMapper.insert(binding);
        return toProjectRepositoryResponse(binding);
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
        return toProjectRepositoryResponse(current);
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
     * 处理GitHub App安装回调
     * 
     * @param providerInstallationId GitHub App安装ID
     * @param state                  状态
     */
    @Transactional
    public void handleInstallationCallback(long providerInstallationId, String state) {
        log.info("Handling GitHub App installation callback. providerInstallationId: {}", providerInstallationId);
        // 验证state并获取 teamId
        UUID teamId = gitHubClient.verifyInstallationState(state);
        // 获取安装详情
        GitHubInstallationDetails installation = gitHubClient.getInstallation(providerInstallationId);
        // 获取安装记录
        GitHubInstallationEntity installationEntity = installationMapper.selectOne(
                new LambdaQueryWrapper<GitHubInstallationEntity>().eq(
                        GitHubInstallationEntity::getProviderInstallationId,
                        providerInstallationId));
        // 如果不存在，创建新记录
        boolean newInstallation = installationEntity == null;
        if (newInstallation) {
            installationEntity = new GitHubInstallationEntity();
            installationEntity.setId(UUID.randomUUID());
        }
        installationEntity.setTeamId(teamId);
        installationEntity.setProviderInstallationId(installation.getInstallationId());
        installationEntity.setAccountLogin(installation.getAccountLogin());
        installationEntity.setAccountType(normalizeEnum(installation.getAccountType()));
        installationEntity.setStatus("ACTIVE");
        if (newInstallation) {
            installationMapper.insert(installationEntity);
        } else {
            installationMapper.updateById(installationEntity);
        }

        // 查看当时被授权的仓库，然后遍历更新
        for (GitHubRepositoryDetails repository : gitHubClient.listRepositories(providerInstallationId)) {
            GitHubRepositoryEntity repositoryEntity = repositoryMapper.selectOne(
                    new LambdaQueryWrapper<GitHubRepositoryEntity>().eq(GitHubRepositoryEntity::getProviderRepositoryId,
                            repository.getRepositoryId()));
            boolean newRepository = repositoryEntity == null;
            if (newRepository) {
                repositoryEntity = new GitHubRepositoryEntity();
                repositoryEntity.setId(UUID.randomUUID());
            }
            repositoryEntity.setInstallationId(installationEntity.getId());
            repositoryEntity.setProviderRepositoryId(repository.getRepositoryId());
            repositoryEntity.setOwnerLogin(repository.getOwnerLogin());
            repositoryEntity.setName(repository.getName());
            repositoryEntity.setDefaultBranch(repository.getDefaultBranch());
            repositoryEntity.setVisibility(normalizeEnum(repository.getVisibility()));
            repositoryEntity.setArchived(repository.isArchived());
            repositoryEntity.setSyncedAt(LocalDateTime.now(clock));
            if (newRepository) {
                repositoryMapper.insert(repositoryEntity);
            } else {
                repositoryMapper.updateById(repositoryEntity);
            }
        }
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
                        .eq(GitHubRepositoryEntity::getArchived, false));
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
                installation.getUpdatedAt());
    }

    private GitHubRepositoryResponse toRepositoryResponse(GitHubRepositoryEntity repository) {
        return new GitHubRepositoryResponse(repository.getId(), repository.getProviderRepositoryId(),
                repository.getOwnerLogin(), repository.getName(), repository.getDefaultBranch(),
                repository.getVisibility(),
                Boolean.TRUE.equals(repository.getArchived()), repository.getSyncedAt());
    }

    private ProjectRepositoryResponse toProjectRepositoryResponse(ProjectRepositoryEntity binding) {
        return new ProjectRepositoryResponse(binding.getId(), binding.getRepositoryId(),
                binding.getDefaultBranch(), binding.getDisplayName(), binding.getBoundAt());
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
