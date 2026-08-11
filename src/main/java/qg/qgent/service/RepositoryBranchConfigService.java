package qg.qgent.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import qg.qgent.api.ApiException;
import qg.qgent.dto.BranchPolicyDto;
import qg.qgent.dto.QualityGateDto;
import qg.qgent.dto.UpdateBranchPolicyRequest;
import qg.qgent.dto.UpdateQualityGateRequest;
import qg.qgent.entity.BranchPolicyJson;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.RepositoryBranchConfigEntity;
import qg.qgent.entity.RepositoryBranchConfigTestsetEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.RepositoryBranchConfigMapper;
import qg.qgent.mapper.RepositoryBranchConfigTestsetMapper;
import qg.qgent.mapper.TeamMemberMapper;

@Service
public class RepositoryBranchConfigService {
    private final RepositoryBranchConfigMapper branchConfigMapper;
    private final RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMemberMapper teamMemberMapper;

    public RepositoryBranchConfigService(RepositoryBranchConfigMapper branchConfigMapper,
                                         RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper,
                                         ProjectRepositoryMapper projectRepositoryMapper,
                                         ProjectMapper projectMapper,
                                         ProjectMemberMapper projectMemberMapper,
                                         TeamMemberMapper teamMemberMapper) {
        this.branchConfigMapper = branchConfigMapper;
        this.branchConfigTestsetMapper = branchConfigTestsetMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.teamMemberMapper = teamMemberMapper;
    }

    public BranchPolicyDto getBranchPolicy(UUID actorId, UUID projectId, UUID repositoryId, String branchName) {
        requireProjectMember(actorId, projectId);
        ProjectRepositoryEntity projectRepo = getProjectRepository(projectId, repositoryId);
        RepositoryBranchConfigEntity config = getOrCreateConfig(projectRepo.getId(), branchName);

        BranchPolicyDto dto = new BranchPolicyDto();
        if (config.getPolicyJson() != null) {
            java.util.Map<String, Object> p = config.getPolicyJson();
            dto.setRequirePullRequest(p.containsKey("requirePullRequest") ? (Boolean) p.get("requirePullRequest") : null);
            dto.setMinimumHumanApprovals(p.containsKey("minimumHumanApprovals") ? (Integer) p.get("minimumHumanApprovals") : null);
            dto.setAllowDirectPush(p.containsKey("allowDirectPush") ? (Boolean) p.get("allowDirectPush") : null);
        }
        return dto;
    }

    @Transactional
    public BranchPolicyDto updateBranchPolicy(UUID actorId, UUID projectId, UUID repositoryId, String branchName, UpdateBranchPolicyRequest request) {
        requireProjectAdmin(actorId, projectId);
        ProjectRepositoryEntity projectRepo = getProjectRepository(projectId, repositoryId);
        RepositoryBranchConfigEntity config = getOrCreateConfig(projectRepo.getId(), branchName);

        java.util.Map<String, Object> policyJson = config.getPolicyJson();
        if (policyJson == null) {
            policyJson = new java.util.HashMap<>();
        }
        if (request.getRequirePullRequest() != null) policyJson.put("requirePullRequest", request.getRequirePullRequest());
        if (request.getMinimumHumanApprovals() != null) policyJson.put("minimumHumanApprovals", request.getMinimumHumanApprovals());
        if (request.getAllowDirectPush() != null) policyJson.put("allowDirectPush", request.getAllowDirectPush());
        config.setPolicyJson(policyJson);
        config.setUpdatedAt(LocalDateTime.now());
        
        branchConfigMapper.updateById(config);
        
        BranchPolicyDto dto = new BranchPolicyDto();
        dto.setRequirePullRequest(policyJson.containsKey("requirePullRequest") ? (Boolean) policyJson.get("requirePullRequest") : null);
        dto.setMinimumHumanApprovals(policyJson.containsKey("minimumHumanApprovals") ? (Integer) policyJson.get("minimumHumanApprovals") : null);
        dto.setAllowDirectPush(policyJson.containsKey("allowDirectPush") ? (Boolean) policyJson.get("allowDirectPush") : null);
        return dto;
    }

    public QualityGateDto getQualityGate(UUID actorId, UUID projectId, UUID repositoryId, String branchName) {
        requireProjectMember(actorId, projectId);
        ProjectRepositoryEntity projectRepo = getProjectRepository(projectId, repositoryId);
        RepositoryBranchConfigEntity config = getOrCreateConfig(projectRepo.getId(), branchName);

        QualityGateDto dto = new QualityGateDto();
        dto.setRequiredChecks(config.getRequiredChecks() != null ? config.getRequiredChecks() : List.of());
        
        List<UUID> testsetIds = branchConfigTestsetMapper.selectList(new LambdaQueryWrapper<RepositoryBranchConfigTestsetEntity>().eq(RepositoryBranchConfigTestsetEntity::getBranchConfigId, config.getId()))
                .stream()
                .map(RepositoryBranchConfigTestsetEntity::getTestsetId)
                .collect(Collectors.toList());
        dto.setRequiredTestsetIds(testsetIds);
        
        return dto;
    }

    @Transactional
    public QualityGateDto updateQualityGate(UUID actorId, UUID projectId, UUID repositoryId, String branchName, UpdateQualityGateRequest request) {
        requireProjectAdmin(actorId, projectId);
        ProjectRepositoryEntity projectRepo = getProjectRepository(projectId, repositoryId);
        RepositoryBranchConfigEntity config = getOrCreateConfig(projectRepo.getId(), branchName);

        config.setRequiredChecks(request.getRequiredChecks());
        config.setUpdatedAt(LocalDateTime.now());
        branchConfigMapper.updateById(config);
        
        branchConfigTestsetMapper.delete(new LambdaQueryWrapper<RepositoryBranchConfigTestsetEntity>().eq(RepositoryBranchConfigTestsetEntity::getBranchConfigId, config.getId()));
                
        if (request.getRequiredTestsetIds() != null) {
            for (UUID testsetId : request.getRequiredTestsetIds()) {
                RepositoryBranchConfigTestsetEntity testsetEntity = new RepositoryBranchConfigTestsetEntity();
                testsetEntity.setBranchConfigId(config.getId());
                testsetEntity.setTestsetId(testsetId);
                branchConfigTestsetMapper.insert(testsetEntity);
            }
        }
        
        QualityGateDto dto = new QualityGateDto();
        dto.setRequiredChecks(request.getRequiredChecks());
        dto.setRequiredTestsetIds(request.getRequiredTestsetIds() != null ? request.getRequiredTestsetIds() : List.of());
        return dto;
    }

    private RepositoryBranchConfigEntity getOrCreateConfig(UUID projectRepositoryId, String branchName) {
        RepositoryBranchConfigEntity config = branchConfigMapper.selectOne(new LambdaQueryWrapper<RepositoryBranchConfigEntity>()
                .eq(RepositoryBranchConfigEntity::getProjectRepositoryId, projectRepositoryId)
                .eq(RepositoryBranchConfigEntity::getBranchName, branchName));
        if (config == null) {
            config = new RepositoryBranchConfigEntity();
            config.setId(UUID.randomUUID());
            config.setProjectRepositoryId(projectRepositoryId);
            config.setBranchName(branchName);
            config.setCreatedAt(LocalDateTime.now());
            config.setUpdatedAt(LocalDateTime.now());
            branchConfigMapper.insert(config);
        }
        return config;
    }

    private ProjectRepositoryEntity getProjectRepository(UUID projectId, UUID repositoryId) {
        ProjectRepositoryEntity projectRepo = projectRepositoryMapper.selectOne(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                .eq(ProjectRepositoryEntity::getProjectId, projectId)
                .eq(ProjectRepositoryEntity::getRepositoryId, repositoryId));
        if (projectRepo == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_REPOSITORY_NOT_FOUND", "Project repository binding not found");
        }
        return projectRepo;
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
                || projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMemberEntity>().eq(ProjectMemberEntity::getProjectId, projectId).eq(ProjectMemberEntity::getUserId, actorId)) > 0);
    }

    private boolean hasProjectAdminAccess(UUID projectId, UUID actorId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        return project != null && (isTeamOwner(project.getTeamId(), actorId)
                || projectMemberMapper.selectCount(new LambdaQueryWrapper<ProjectMemberEntity>().eq(ProjectMemberEntity::getProjectId, projectId).eq(ProjectMemberEntity::getUserId, actorId).eq(ProjectMemberEntity::getRole, "PROJECT_ADMIN")) > 0);
    }

    private boolean isTeamOwner(UUID teamId, UUID actorId) {
        return teamMemberMapper.selectCount(new LambdaQueryWrapper<TeamMemberEntity>().eq(TeamMemberEntity::getTeamId, teamId).eq(TeamMemberEntity::getUserId, actorId).eq(TeamMemberEntity::getRole, "TEAM_OWNER")) > 0;
    }

    private ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }
}
