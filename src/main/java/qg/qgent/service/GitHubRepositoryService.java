package qg.qgent.service;

import java.time.Clock;
import java.time.Instant;
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

    public GitHubInstallationUrlResponse createInstallationUrl(UUID actorId, UUID teamId) {
        log.info("Generating GitHub installation URL for teamId: {}, requested by actorId: {}", teamId, actorId);
        requireTeamOwner(actorId, teamId);
        return new GitHubInstallationUrlResponse(gitHubClient.createInstallationUrl(teamId, actorId),
                Instant.now(clock).plusSeconds(600));
    }

    public List<GitHubInstallationResponse> listInstallations(UUID actorId, UUID teamId) {
        requireTeamOwner(actorId, teamId);
        return installationMapper.selectList(new LambdaQueryWrapper<GitHubInstallationEntity>()
                        .eq(GitHubInstallationEntity::getTeamId, teamId)
                        .orderByDesc(GitHubInstallationEntity::getUpdatedAt))
                .stream().map(this::toInstallationResponse).toList();
    }

    @Transactional
    public void removeInstallation(UUID actorId, UUID teamId, UUID installationId) {
        requireTeamOwner(actorId, teamId);
        GitHubInstallationEntity installation = installationMapper.selectOne(new LambdaQueryWrapper<GitHubInstallationEntity>()
                .eq(GitHubInstallationEntity::getId, installationId)
                .eq(GitHubInstallationEntity::getTeamId, teamId));
        if (installation == null) {
            throw notFound("GitHub installation does not exist");
        }
        List<UUID> repositoryIds = repositoryMapper.selectList(new LambdaQueryWrapper<GitHubRepositoryEntity>()
                        .eq(GitHubRepositoryEntity::getInstallationId, installationId)
                        .select(GitHubRepositoryEntity::getId))
                .stream().map(GitHubRepositoryEntity::getId).toList();
        if (!repositoryIds.isEmpty() && projectRepositoryMapper.selectCount(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                .in(ProjectRepositoryEntity::getRepositoryId, repositoryIds)) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_INSTALLATION_IN_USE",
                    "Unbind project repositories before removing this GitHub installation");
        }
        installationMapper.deleteById(installationId);
    }

    public List<GitHubRepositoryResponse> listTeamRepositories(UUID actorId, UUID teamId) {
        if (!hasTeamRepositoryAccess(teamId, actorId)) {
            throw forbidden("Team owner or project admin access is required");
        }
        return findActiveRepositoriesByTeam(teamId).stream().map(this::toRepositoryResponse).toList();
    }

    public List<ProjectRepositoryResponse> listProjectRepositories(UUID actorId, UUID projectId) {
        requireProjectMember(actorId, projectId);
        return projectRepositoryMapper.selectList(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                        .eq(ProjectRepositoryEntity::getProjectId, projectId)
                        .orderByDesc(ProjectRepositoryEntity::getBoundAt))
                .stream().map(this::toProjectRepositoryResponse).toList();
    }

    @Transactional
    public ProjectRepositoryResponse bindProjectRepository(UUID actorId, UUID projectId,
                                                           BindProjectRepositoryRequest request) {
        log.info("Binding GitHub repository (ID: {}) to projectId: {} by actorId: {}", request.getRepositoryId(), projectId, actorId);
        requireProjectAdmin(actorId, projectId);
        GitHubRepositoryEntity repository = findActiveRepositoryForProject(request.getInstallationId(),
                request.getRepositoryId(), projectId);
        if (repository == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPOSITORY_NOT_AUTHORIZED_FOR_PROJECT",
                    "Repository is not available through an active installation for this project team");
        }
        if (projectRepositoryMapper.selectOne(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                .eq(ProjectRepositoryEntity::getProjectId, projectId)
                .eq(ProjectRepositoryEntity::getRepositoryId, repository.getId())) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_REPOSITORY_ALREADY_BOUND",
                    "Repository is already bound to this project");
        }
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(UUID.randomUUID());
        binding.setProjectId(projectId);
        binding.setRepositoryId(repository.getId());
        binding.setDefaultBranch(blankToDefault(request.getDefaultBranch(), repository.getDefaultBranch()));
        binding.setDisplayName(request.getDisplayName());
        binding.setBoundAt(Instant.now(clock));
        projectRepositoryMapper.insert(binding);
        return toProjectRepositoryResponse(binding);
    }

    @Transactional
    public ProjectRepositoryResponse updateProjectRepository(UUID actorId, UUID projectId, UUID projectRepositoryId,
                                                             UpdateProjectRepositoryRequest request) {
        requireProjectAdmin(actorId, projectId);
        ProjectRepositoryEntity current = projectRepositoryMapper.selectById(projectRepositoryId);
        if (current == null || !projectId.equals(current.getProjectId())) {
            throw notFound("Project repository binding does not exist");
        }
        current.setDefaultBranch(request.getDefaultBranch());
        current.setDisplayName(request.getDisplayName());
        projectRepositoryMapper.updateById(current);
        return toProjectRepositoryResponse(current);
    }

    @Transactional
    public void unbindProjectRepository(UUID actorId, UUID projectId, UUID projectRepositoryId) {
        requireProjectAdmin(actorId, projectId);
        ProjectRepositoryEntity current = projectRepositoryMapper.selectById(projectRepositoryId);
        if (current == null || !projectId.equals(current.getProjectId())) {
            throw notFound("Project repository binding does not exist");
        }
        if (branchConfigMapper.selectCount(new LambdaQueryWrapper<RepositoryBranchConfigEntity>()
                .eq(RepositoryBranchConfigEntity::getProjectRepositoryId, projectRepositoryId)) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_REPOSITORY_REFERENCED_BY_BRANCH_CONFIG",
                    "Delete branch configuration before unbinding this repository");
        }
        projectRepositoryMapper.deleteById(projectRepositoryId);
    }

    @Transactional
    public void handleInstallationCallback(long providerInstallationId, String state) {
        log.info("Handling GitHub App installation callback. providerInstallationId: {}", providerInstallationId);
        UUID teamId = gitHubClient.verifyInstallationState(state);
        GitHubInstallationDetails installation = gitHubClient.getInstallation(providerInstallationId);
        GitHubInstallationEntity installationEntity = installationMapper.selectOne(
                new LambdaQueryWrapper<GitHubInstallationEntity>().eq(GitHubInstallationEntity::getProviderInstallationId,
                        providerInstallationId));
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
            repositoryEntity.setSyncedAt(Instant.now(clock));
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
                || projectMemberMapper.countByProjectIdAndUserId(projectId, actorId) > 0);
    }

    private boolean hasProjectAdminAccess(UUID projectId, UUID actorId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        return project != null && (isTeamOwner(project.getTeamId(), actorId)
                || projectMemberMapper.countByProjectIdAndUserIdAndRole(projectId, actorId, "PROJECT_ADMIN") > 0);
    }

    private boolean isTeamOwner(UUID teamId, UUID actorId) {
        return teamMemberMapper.countByTeamIdAndUserIdAndRole(teamId, actorId, "TEAM_OWNER") > 0;
    }

    private boolean hasTeamRepositoryAccess(UUID teamId, UUID actorId) {
        if (isTeamOwner(teamId, actorId)) {
            return true;
        }
        List<UUID> projectIds = projectMapper.selectList(new LambdaQueryWrapper<ProjectEntity>()
                        .eq(ProjectEntity::getTeamId, teamId)
                        .select(ProjectEntity::getId))
                .stream().map(ProjectEntity::getId).toList();
        return projectIds.stream().anyMatch(projectId ->
                projectMemberMapper.countByProjectIdAndUserIdAndRole(projectId, actorId, "PROJECT_ADMIN") > 0);
    }

    private GitHubRepositoryEntity findActiveRepositoryForProject(UUID installationId, UUID repositoryId, UUID projectId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) {
            return null;
        }
        List<UUID> installationIds = activeInstallationIdsForTeam(project.getTeamId());
        return !installationIds.contains(installationId) ? null : repositoryMapper.selectOne(new LambdaQueryWrapper<GitHubRepositoryEntity>()
                .eq(GitHubRepositoryEntity::getId, repositoryId)
                .eq(GitHubRepositoryEntity::getInstallationId, installationId)
                .eq(GitHubRepositoryEntity::getArchived, false));
    }

    private List<GitHubRepositoryEntity> findActiveRepositoriesByTeam(UUID teamId) {
        List<UUID> installationIds = activeInstallationIdsForTeam(teamId);
        return installationIds.isEmpty() ? List.of() : repositoryMapper.selectList(new LambdaQueryWrapper<GitHubRepositoryEntity>()
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
                installation.getAccountLogin(), installation.getAccountType(), installation.getStatus(), installation.getUpdatedAt());
    }

    private GitHubRepositoryResponse toRepositoryResponse(GitHubRepositoryEntity repository) {
        return new GitHubRepositoryResponse(repository.getId(), repository.getProviderRepositoryId(),
                repository.getOwnerLogin(), repository.getName(), repository.getDefaultBranch(), repository.getVisibility(),
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
