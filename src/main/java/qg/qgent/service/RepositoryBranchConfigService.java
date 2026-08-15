package qg.qgent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.dto.BranchPolicyDto;
import qg.qgent.dto.QualityGateDto;
import qg.qgent.dto.UpdateBranchPolicyRequest;
import qg.qgent.dto.UpdateQualityGateRequest;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 6.1 分支策略与质量门禁服务。
 * 负责管理项目级 GitHub 仓库指定分支（如 main/develop）的保护策略（例如是否允许直接 Push、最少需要多少人 Review）
 * 以及质量门禁（必须通过哪些测试集、代码检查等）。
 */
@Service
public class RepositoryBranchConfigService {
    private final RepositoryBranchConfigMapper branchConfigMapper;
    private final RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final TestsetMapper testsetMapper;

    public RepositoryBranchConfigService(RepositoryBranchConfigMapper branchConfigMapper,
                                         RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper,
                                         ProjectRepositoryMapper projectRepositoryMapper,
                                         ProjectMapper projectMapper,
                                         ProjectMemberMapper projectMemberMapper,
                                         TeamMemberMapper teamMemberMapper,
                                         TestsetMapper testsetMapper) {
        this.branchConfigMapper = branchConfigMapper;
        this.branchConfigTestsetMapper = branchConfigTestsetMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.testsetMapper = testsetMapper;
    }

    /**
     * 获取指定分支的保护策略配置。
     *
     * @param actorId      当前操作用户的 ID
     * @param projectId    项目 ID
     * @param repositoryId GitHub 仓库映射在项目内的绑定 ID (ProjectRepositoryId)
     * @param branchName   分支名称（如 "main"）
     * @return 分支保护策略数据传输对象 (BranchPolicyDto)
     */
    public BranchPolicyDto getBranchPolicy(UUID actorId, UUID projectId, UUID repositoryId, String branchName) {
        // 权限校验：至少需要是项目成员才能查看分支策略
        requireProjectMember(actorId, projectId); // 如果不是项目成员，则抛出权限不足异常

        // 获取项目与仓库的绑定记录，确保该仓库真的被这个项目绑定了
        ProjectRepositoryEntity projectRepo = getProjectRepository(projectId, repositoryId);

        // 懒加载设计：获取该分支现有的配置，如果没有则只在内存里返回默认配置
        RepositoryBranchConfigEntity config = getConfig(projectRepo.getId(), branchName, false);

        BranchPolicyDto dto = new BranchPolicyDto();
        if (config.getPolicyJson() != null) {
            Map<String, Object> p = config.getPolicyJson();
            dto.setRequirePullRequest(
                    p.containsKey("requirePullRequest") ? (Boolean) p.get("requirePullRequest") : null);
            dto.setMinimumHumanApprovals(
                    p.containsKey("minimumHumanApprovals") ? (Integer) p.get("minimumHumanApprovals") : null);
            dto.setAllowDirectPush(p.containsKey("allowDirectPush") ? (Boolean) p.get("allowDirectPush") : null);
        }
        return dto;
    }

    /**
     * 更新指定分支的保护策略配置。
     *
     * @param actorId      当前操作用户的 ID
     * @param projectId    项目 ID
     * @param repositoryId GitHub 仓库映射在项目内的绑定 ID
     * @param branchName   分支名称
     * @param request      包含新策略配置的请求对象
     * @return 更新后的分支保护策略
     */
    @Transactional // 开启事务，保证更新过程中发生异常时数据能够回滚
    public BranchPolicyDto updateBranchPolicy(UUID actorId, UUID projectId, UUID repositoryId, String branchName,
                                              UpdateBranchPolicyRequest request) {
        // 权限校验：修改分支策略属于高危操作，必须是项目管理员 (Project Admin) 才能执行
        requireProjectAdmin(actorId, projectId);

        // 获取项目与仓库的绑定记录
        ProjectRepositoryEntity projectRepo = getProjectRepository(projectId, repositoryId);

        // 获取或初始化该分支的配置实体
        RepositoryBranchConfigEntity config = getConfig(projectRepo.getId(), branchName, true);

        Map<String, Object> policyJson = config.getPolicyJson();
        if (policyJson == null) {
            policyJson = new java.util.HashMap<>();
        }
        // 开始根据请求设置
        if (request.getRequirePullRequest() != null)
            policyJson.put("requirePullRequest", request.getRequirePullRequest());
        if (request.getMinimumHumanApprovals() != null)
            policyJson.put("minimumHumanApprovals", request.getMinimumHumanApprovals());
        if (request.getAllowDirectPush() != null)
            policyJson.put("allowDirectPush", request.getAllowDirectPush());
        // 更新到数据库
        config.setPolicyJson(policyJson);
        config.setUpdatedAt(LocalDateTime.now());

        branchConfigMapper.updateById(config);

        // 返回更新后的策略配置
        BranchPolicyDto dto = new BranchPolicyDto();
        dto.setRequirePullRequest(
                policyJson.containsKey("requirePullRequest") ? (Boolean) policyJson.get("requirePullRequest") : null);
        dto.setMinimumHumanApprovals(
                policyJson.containsKey("minimumHumanApprovals") ? (Integer) policyJson.get("minimumHumanApprovals")
                        : null);
        dto.setAllowDirectPush(
                policyJson.containsKey("allowDirectPush") ? (Boolean) policyJson.get("allowDirectPush") : null);
        return dto;
    }

    /**
     * 获取指定分支的质量门禁配置（如必须通过的 Testset 列表、代码检查项）。
     */
    public QualityGateDto getQualityGate(UUID actorId, UUID projectId, UUID repositoryId, String branchName) {
        // 只有项目成员才能查看质量门禁配置
        requireProjectMember(actorId, projectId);

        // 验证仓库绑定关系
        ProjectRepositoryEntity projectRepo = getProjectRepository(projectId, repositoryId);

        // 获取该分支的配置实体（没有则不落库，直接在内存构造）
        RepositoryBranchConfigEntity config = getConfig(projectRepo.getId(), branchName, false);

        QualityGateDto dto = new QualityGateDto();
        dto.setRequiredChecks(config.getRequiredChecks() != null ? config.getRequiredChecks() : List.of());

        List<UUID> testsetIds = List.of();
        if (config.getId() != null) {
            testsetIds = branchConfigTestsetMapper
                    .selectList(new LambdaQueryWrapper<RepositoryBranchConfigTestsetEntity>()
                            .eq(RepositoryBranchConfigTestsetEntity::getBranchConfigId, config.getId()))
                    .stream()
                    .map(RepositoryBranchConfigTestsetEntity::getTestsetId)
                    .collect(Collectors.toList());
        }
        dto.setRequiredTestsetIds(testsetIds);

        return dto;
    }

    /**
     * 更新指定分支的质量门禁配置。
     * 支持全量覆盖现有的必须检查项（Required Checks）和关联的测试集（Testsets）。
     */
    @Transactional
    public QualityGateDto updateQualityGate(UUID actorId, UUID projectId, UUID repositoryId, String branchName,
                                            UpdateQualityGateRequest request) {
        // 必须是项目管理员才能修改门禁
        requireProjectAdmin(actorId, projectId);
        ProjectRepositoryEntity projectRepo = getProjectRepository(projectId, repositoryId);

        if (request.getRequiredTestsetIds() != null && !request.getRequiredTestsetIds().isEmpty()) {
            List<TestsetEntity> testsets = testsetMapper.selectBatchIds(request.getRequiredTestsetIds());
            if (testsets.size() != request.getRequiredTestsetIds().size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TESTSET",
                        "Some required testsets do not exist");
            }
            for (TestsetEntity testset : testsets) {
                if (!testset.getProjectId().equals(projectId)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TESTSET",
                            "Testset does not belong to the project");
                }
                if (testset.getProjectRepositoryId() != null
                        && !testset.getProjectRepositoryId().equals(projectRepo.getId())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TESTSET",
                            "Testset does not belong to the repository");
                }
                if (!"ENABLED".equals(testset.getStatus())) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TESTSET", "Testset is not enabled");
                }
            }
        }

        RepositoryBranchConfigEntity config = getConfig(projectRepo.getId(), branchName, true);

        // 1. 更新主配置表中的 Required Checks 列表
        config.setRequiredChecks(request.getRequiredChecks());
        config.setUpdatedAt(LocalDateTime.now());
        branchConfigMapper.updateById(config);

        branchConfigTestsetMapper.delete(new LambdaQueryWrapper<RepositoryBranchConfigTestsetEntity>()
                .eq(RepositoryBranchConfigTestsetEntity::getBranchConfigId, config.getId()));

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
        dto.setRequiredTestsetIds(
                request.getRequiredTestsetIds() != null ? request.getRequiredTestsetIds() : List.of());
        return dto;
    }

    /**
     * 【内部方法】获取分支配置：如果数据库中已经存在该分支的配置，则直接返回；
     * 否则根据 createIfMissing 决定是否在数据库中初始化，如果不初始化，只在内存中返回一个空配置
     *
     * @param projectRepositoryId 仓库绑定关系 ID
     * @param branchName          分支名称
     * @param createIfMissing     如果不存在，是否要写入数据库
     * @return 分支配置实体
     */
    private RepositoryBranchConfigEntity getConfig(UUID projectRepositoryId, String branchName,
                                                   boolean createIfMissing) {
        // 尝试从数据库查询该分支配置
        RepositoryBranchConfigEntity config = branchConfigMapper
                .selectOne(new LambdaQueryWrapper<RepositoryBranchConfigEntity>()
                        .eq(RepositoryBranchConfigEntity::getProjectRepositoryId, projectRepositoryId)
                        .eq(RepositoryBranchConfigEntity::getBranchName, branchName));

        // 如果不存在，则进行初始化
        if (config == null) {
            config = new RepositoryBranchConfigEntity();
            if (createIfMissing) {
                config.setId(UUID.randomUUID());
            }
            config.setProjectRepositoryId(projectRepositoryId);
            config.setBranchName(branchName);
            config.setCreatedAt(LocalDateTime.now());
            config.setUpdatedAt(LocalDateTime.now());
            if (createIfMissing) {
                branchConfigMapper.insert(config);
            }
        }
        return config;
    }

    /**
     * 【内部方法】根据项目 ID 和 GitHub 仓库 ID 获取绑定关系。
     * 会同时校验该仓库是否真的被该项目绑定。
     */
    private ProjectRepositoryEntity getProjectRepository(UUID projectId, UUID repositoryId) {
        ProjectRepositoryEntity projectRepo = projectRepositoryMapper
                .selectOne(new LambdaQueryWrapper<ProjectRepositoryEntity>()
                        .eq(ProjectRepositoryEntity::getProjectId, projectId)
                        .eq(ProjectRepositoryEntity::getRepositoryId, repositoryId));
        if (projectRepo == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_REPOSITORY_NOT_FOUND",
                    "Project repository binding not found");
        }
        return projectRepo;
    }

    /**
     * 【内部方法】强制校验用户是否是项目成员（或团队老大）。若校验不通过则抛出 403 异常。
     */
    private void requireProjectMember(UUID actorId, UUID projectId) {
        if (!hasProjectAccess(projectId, actorId)) {
            throw forbidden("Project member access is required");
        }
    }

    /**
     * 【内部方法】强制校验用户是否是项目管理员（Project Admin）或团队老大。若校验不通过则抛出 403 异常。
     */
    private void requireProjectAdmin(UUID actorId, UUID projectId) {
        if (!hasProjectAdminAccess(projectId, actorId)) {
            throw forbidden("Project admin access is required");
        }
    }

    /**
     * 【内部方法】判断用户是否拥有该项目的访问权限。
     * 判断条件：1. 项目存在； 2. 用户是项目的团队老大 (Team Owner) 或 被明确添加到该项目 (Project Member)
     */
    private boolean hasProjectAccess(UUID projectId, UUID actorId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        return project != null && (isTeamOwner(project.getTeamId(), actorId)
                || projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMemberEntity>().eq(ProjectMemberEntity::getProjectId, projectId)
                        .eq(ProjectMemberEntity::getUserId, actorId)) > 0);
        // 项目是否存在+是团队老大/是普通成员
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

    private ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", message);
    }
}
