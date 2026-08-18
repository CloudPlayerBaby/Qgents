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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 6.1 鍒嗘敮绛栫暐涓庤川閲忛棬绂佹湇鍔°€?
 * 璐熻矗绠＄悊椤圭洰绾?GitHub 浠撳簱鎸囧畾鍒嗘敮锛堝 main/develop锛夌殑淇濇姢绛栫暐锛堜緥濡傛槸鍚﹀厑璁哥洿鎺?Push銆佹渶灏戦渶瑕佸灏戜汉 Review锛?
 * 浠ュ強璐ㄩ噺闂ㄧ锛堝繀椤婚€氳繃鍝簺娴嬭瘯闆嗐€佷唬鐮佹鏌ョ瓑锛夈€?
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
    private final GitStoreSyncService gitStores;

    public RepositoryBranchConfigService(RepositoryBranchConfigMapper branchConfigMapper,
                                         RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper,
                                         ProjectRepositoryMapper projectRepositoryMapper,
                                         ProjectMapper projectMapper,
                                         ProjectMemberMapper projectMemberMapper,
                                         TeamMemberMapper teamMemberMapper,
                                         TestsetMapper testsetMapper,
                                         GitStoreSyncService gitStores) {
        this.branchConfigMapper = branchConfigMapper;
        this.branchConfigTestsetMapper = branchConfigTestsetMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.testsetMapper = testsetMapper;
        this.gitStores = gitStores;
    }

    /**
     * 鑾峰彇鎸囧畾鍒嗘敮鐨勪繚鎶ょ瓥鐣ラ厤缃€?
     *
     * @param actorId      褰撳墠鎿嶄綔鐢ㄦ埛鐨?ID
     * @param projectId    椤圭洰 ID
     * @param repositoryId GitHub 浠撳簱鏄犲皠鍦ㄩ」鐩唴鐨勭粦瀹?ID (ProjectRepositoryId)
     * @param branchName   鍒嗘敮鍚嶇О锛堝 "main"锛?
     * @return 鍒嗘敮淇濇姢绛栫暐鏁版嵁浼犺緭瀵硅薄 (BranchPolicyDto)
     */
    public BranchPolicyDto getBranchPolicy(UUID actorId, UUID projectId, UUID repositoryId, String branchName) {
        branchName = gitStores.normalizeTargetBranch(branchName);
        // 鏉冮檺鏍￠獙锛氳嚦灏戦渶瑕佹槸椤圭洰鎴愬憳鎵嶈兘鏌ョ湅鍒嗘敮绛栫暐
        requireProjectMember(actorId, projectId); // 濡傛灉涓嶆槸椤圭洰鎴愬憳锛屽垯鎶涘嚭鏉冮檺涓嶈冻寮傚父

        // 鑾峰彇椤圭洰涓庝粨搴撶殑缁戝畾璁板綍锛岀‘淇濊浠撳簱鐪熺殑琚繖涓」鐩粦瀹氫簡
        ProjectRepositoryEntity projectRepo = getProjectRepository(projectId, repositoryId);

        // 鎳掑姞杞借璁★細鑾峰彇璇ュ垎鏀幇鏈夌殑閰嶇疆锛屽鏋滄病鏈夊垯鍙湪鍐呭瓨閲岃繑鍥為粯璁ら厤缃?
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
     * 鏇存柊鎸囧畾鍒嗘敮鐨勪繚鎶ょ瓥鐣ラ厤缃€?
     *
     * @param actorId      褰撳墠鎿嶄綔鐢ㄦ埛鐨?ID
     * @param projectId    椤圭洰 ID
     * @param repositoryId GitHub 浠撳簱鏄犲皠鍦ㄩ」鐩唴鐨勭粦瀹?ID
     * @param branchName   鍒嗘敮鍚嶇О
     * @param request      鍖呭惈鏂扮瓥鐣ラ厤缃殑璇锋眰瀵硅薄
     * @return 鏇存柊鍚庣殑鍒嗘敮淇濇姢绛栫暐
     */
    @Transactional // 寮€鍚簨鍔★紝淇濊瘉鏇存柊杩囩▼涓彂鐢熷紓甯告椂鏁版嵁鑳藉鍥炴粴
    public BranchPolicyDto updateBranchPolicy(UUID actorId, UUID projectId, UUID repositoryId, String branchName,
                                              UpdateBranchPolicyRequest request) {
        branchName = gitStores.normalizeTargetBranch(branchName);
        // 鏉冮檺鏍￠獙锛氫慨鏀瑰垎鏀瓥鐣ュ睘浜庨珮鍗辨搷浣滐紝蹇呴』鏄」鐩鐞嗗憳 (Project Admin) 鎵嶈兘鎵ц
        requireProjectAdmin(actorId, projectId);

        // 鑾峰彇椤圭洰涓庝粨搴撶殑缁戝畾璁板綍
        ProjectRepositoryEntity projectRepo = getProjectRepository(projectId, repositoryId);

        // 鑾峰彇鎴栧垵濮嬪寲璇ュ垎鏀殑閰嶇疆瀹炰綋
        RepositoryBranchConfigEntity config = getConfig(projectRepo.getId(), branchName, true);

        Map<String, Object> policyJson = config.getPolicyJson();
        if (policyJson == null) {
            policyJson = new java.util.HashMap<>();
        }
        // 寮€濮嬫牴鎹姹傝缃?
        if (request.getRequirePullRequest() != null)
            policyJson.put("requirePullRequest", request.getRequirePullRequest());
        if (request.getMinimumHumanApprovals() != null)
            policyJson.put("minimumHumanApprovals", request.getMinimumHumanApprovals());
        if (request.getAllowDirectPush() != null)
            policyJson.put("allowDirectPush", request.getAllowDirectPush());
        // 鏇存柊鍒版暟鎹簱
        config.setPolicyJson(policyJson);
        config.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        branchConfigMapper.updateById(config);

        // 杩斿洖鏇存柊鍚庣殑绛栫暐閰嶇疆
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
     * 鑾峰彇鎸囧畾鍒嗘敮鐨勮川閲忛棬绂侀厤缃紙濡傚繀椤婚€氳繃鐨?Testset 鍒楄〃銆佷唬鐮佹鏌ラ」锛夈€?
     */
    public QualityGateDto getQualityGate(UUID actorId, UUID projectId, UUID repositoryId, String branchName) {
        branchName = gitStores.normalizeTargetBranch(branchName);
        // 鍙湁椤圭洰鎴愬憳鎵嶈兘鏌ョ湅璐ㄩ噺闂ㄧ閰嶇疆
        requireProjectMember(actorId, projectId);

        // 楠岃瘉浠撳簱缁戝畾鍏崇郴
        ProjectRepositoryEntity projectRepo = getProjectRepository(projectId, repositoryId);

        // 鑾峰彇璇ュ垎鏀殑閰嶇疆瀹炰綋锛堟病鏈夊垯涓嶈惤搴擄紝鐩存帴鍦ㄥ唴瀛樻瀯閫狅級
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
     * 鏇存柊鎸囧畾鍒嗘敮鐨勮川閲忛棬绂侀厤缃€?
     * 鏀寔鍏ㄩ噺瑕嗙洊鐜版湁鐨勫繀椤绘鏌ラ」锛圧equired Checks锛夊拰鍏宠仈鐨勬祴璇曢泦锛圱estsets锛夈€?
     */
    @Transactional
    public QualityGateDto updateQualityGate(UUID actorId, UUID projectId, UUID repositoryId, String branchName,
                                            UpdateQualityGateRequest request) {
        branchName = gitStores.normalizeTargetBranch(branchName);
        // 蹇呴』鏄」鐩鐞嗗憳鎵嶈兘淇敼闂ㄧ
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

        // 1. 鏇存柊涓婚厤缃〃涓殑 Required Checks 鍒楄〃
        config.setRequiredChecks(request.getRequiredChecks());
        config.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
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
     * 銆愬唴閮ㄦ柟娉曘€戣幏鍙栧垎鏀厤缃細濡傛灉鏁版嵁搴撲腑宸茬粡瀛樺湪璇ュ垎鏀殑閰嶇疆锛屽垯鐩存帴杩斿洖锛?
     * 鍚﹀垯鏍规嵁 createIfMissing 鍐冲畾鏄惁鍦ㄦ暟鎹簱涓垵濮嬪寲锛屽鏋滀笉鍒濆鍖栵紝鍙湪鍐呭瓨涓繑鍥炰竴涓┖閰嶇疆
     *
     * @param projectRepositoryId 浠撳簱缁戝畾鍏崇郴 ID
     * @param branchName          鍒嗘敮鍚嶇О
     * @param createIfMissing     濡傛灉涓嶅瓨鍦紝鏄惁瑕佸啓鍏ユ暟鎹簱
     * @return 鍒嗘敮閰嶇疆瀹炰綋
     */
    private RepositoryBranchConfigEntity getConfig(UUID projectRepositoryId, String branchName,
                                                   boolean createIfMissing) {
        // 灏濊瘯浠庢暟鎹簱鏌ヨ璇ュ垎鏀厤缃?
        RepositoryBranchConfigEntity config = branchConfigMapper
                .selectOne(new LambdaQueryWrapper<RepositoryBranchConfigEntity>()
                        .eq(RepositoryBranchConfigEntity::getProjectRepositoryId, projectRepositoryId)
                        .eq(RepositoryBranchConfigEntity::getBranchName, branchName));

        // 濡傛灉涓嶅瓨鍦紝鍒欒繘琛屽垵濮嬪寲
        if (config == null) {
            config = new RepositoryBranchConfigEntity();
            if (createIfMissing) {
                config.setId(UUID.randomUUID());
            }
            config.setProjectRepositoryId(projectRepositoryId);
            config.setBranchName(branchName);
            config.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            config.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            if (createIfMissing) {
                branchConfigMapper.insert(config);
            }
        }
        return config;
    }

    /**
     * 銆愬唴閮ㄦ柟娉曘€戞牴鎹」鐩?ID 鍜?GitHub 浠撳簱 ID 鑾峰彇缁戝畾鍏崇郴銆?
     * 浼氬悓鏃舵牎楠岃浠撳簱鏄惁鐪熺殑琚椤圭洰缁戝畾銆?
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
        // 软解绑后的仓库不再可配置：历史配置保留只读，写入需先重新绑定
        if ("UNBOUND".equals(projectRepo.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "PROJECT_REPOSITORY_UNBOUND",
                    "Project repository binding is unbound");
        }
        return projectRepo;
    }

    /**
     * 銆愬唴閮ㄦ柟娉曘€戝己鍒舵牎楠岀敤鎴锋槸鍚︽槸椤圭洰鎴愬憳锛堟垨鍥㈤槦鑰佸ぇ锛夈€傝嫢鏍￠獙涓嶉€氳繃鍒欐姏鍑?403 寮傚父銆?
     */
    private void requireProjectMember(UUID actorId, UUID projectId) {
        if (!hasProjectAccess(projectId, actorId)) {
            throw forbidden("Project member access is required");
        }
    }

    /**
     * 銆愬唴閮ㄦ柟娉曘€戝己鍒舵牎楠岀敤鎴锋槸鍚︽槸椤圭洰绠＄悊鍛橈紙Project Admin锛夋垨鍥㈤槦鑰佸ぇ銆傝嫢鏍￠獙涓嶉€氳繃鍒欐姏鍑?403 寮傚父銆?
     */
    private void requireProjectAdmin(UUID actorId, UUID projectId) {
        if (!hasProjectAdminAccess(projectId, actorId)) {
            throw forbidden("Project admin access is required");
        }
    }

    /**
     * 銆愬唴閮ㄦ柟娉曘€戝垽鏂敤鎴锋槸鍚︽嫢鏈夎椤圭洰鐨勮闂潈闄愩€?
     * 鍒ゆ柇鏉′欢锛?. 椤圭洰瀛樺湪锛?2. 鐢ㄦ埛鏄」鐩殑鍥㈤槦鑰佸ぇ (Team Owner) 鎴?琚槑纭坊鍔犲埌璇ラ」鐩?(Project Member)
     */
    private boolean hasProjectAccess(UUID projectId, UUID actorId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        return project != null && (isTeamOwner(project.getTeamId(), actorId)
                || projectMemberMapper.selectCount(
                new LambdaQueryWrapper<ProjectMemberEntity>().eq(ProjectMemberEntity::getProjectId, projectId)
                        .eq(ProjectMemberEntity::getUserId, actorId)) > 0);
        // 椤圭洰鏄惁瀛樺湪+鏄洟闃熻€佸ぇ/鏄櫘閫氭垚鍛?
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
