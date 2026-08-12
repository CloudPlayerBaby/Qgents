package qg.qgent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.MergeRequestCheckResponse;
import qg.qgent.dto.MergeRequestCreateRequest;
import qg.qgent.dto.MergeRequestDetailResponse;
import qg.qgent.dto.MergeRequestReviewResponse;
import qg.qgent.dto.MergeRequestSummaryResponse;
import qg.qgent.dto.PageMeta;
import qg.qgent.dto.QualityGateResponse;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.MergeRequestGroupEntity;
import qg.qgent.entity.MergeRequestReviewEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.QualityCheckResultEntity;
import qg.qgent.entity.RepositoryBranchConfigEntity;
import qg.qgent.entity.RepositoryBranchConfigTestsetEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.mapper.MergeRequestGroupMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.MergeRequestReviewMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.QualityCheckResultMapper;
import qg.qgent.mapper.RepositoryBranchConfigMapper;
import qg.qgent.mapper.RepositoryBranchConfigTestsetMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * MR 镜像、审查与质量门禁服务。
 * MR belongs to one repository and is derived from the persisted source branch
 * and head commit
 * of a Task Workspace. Client-supplied credentials, commit SHAs and gate
 * outcomes are not trusted.
 * qualityGate 汇总：从目标分支 branch config 的 required_checks + 必选测试集取必检项，
 * 对照 quality_check_results 在 headCommit 的最新 attempt_no；全部 PASSED → PASSED，
 * 任一 FAILED → FAILED，缺失或运行中 → PENDING。
 */
@Service
public class MergeRequestService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final MergeRequestMapper mergeRequestMapper;
    private final MergeRequestGroupMapper mergeRequestGroupMapper;
    private final QualityCheckResultMapper qualityCheckMapper;
    private final MergeRequestReviewMapper reviewMapper;
    private final TaskMapper taskMapper;
    private final WorkspaceRepositoryMapper workspaceRepositoryMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final RepositoryBranchConfigMapper branchConfigMapper;
    private final RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper;
    private final ProjectAccessService projectAccess;
    private final EventService eventService;

    public MergeRequestService(MergeRequestMapper mergeRequestMapper, MergeRequestGroupMapper mergeRequestGroupMapper,
            QualityCheckResultMapper qualityCheckMapper, MergeRequestReviewMapper reviewMapper,
            TaskMapper taskMapper, WorkspaceRepositoryMapper workspaceRepositoryMapper,
            ProjectRepositoryMapper projectRepositoryMapper,
            RepositoryBranchConfigMapper branchConfigMapper,
            RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper, ProjectAccessService projectAccess,
            EventService eventService) {
        this.mergeRequestMapper = mergeRequestMapper;
        this.mergeRequestGroupMapper = mergeRequestGroupMapper;
        this.qualityCheckMapper = qualityCheckMapper;
        this.reviewMapper = reviewMapper;
        this.taskMapper = taskMapper;
        this.workspaceRepositoryMapper = workspaceRepositoryMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.branchConfigMapper = branchConfigMapper;
        this.branchConfigTestsetMapper = branchConfigTestsetMapper;
        this.projectAccess = projectAccess;
        this.eventService = eventService;
    }

    /**
     * 查询项目关联 MR，支持仓库、需求群、状态过滤（游标分页）。
     */
    public ApiPageResponse<MergeRequestSummaryResponse> list(UUID projectId, UUID userId, UUID repositoryId,
            UUID groupId, String status, String cursor, int limit, String requestId) {
        projectAccess.requireProjectMember(projectId, userId);
        int size = clampLimit(limit);
        List<UUID> repoIds = projectRepositoryMapper.selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                .eq(ProjectRepositoryEntity::getProjectId, projectId)).stream()
                .map(ProjectRepositoryEntity::getId).toList();
        if (repoIds.isEmpty()) {
            return emptyPage(requestId);
        }
        UUID cursorUuid = parseCursor(cursor);
        LambdaQueryWrapper<MergeRequestEntity> query = Wrappers.<MergeRequestEntity>lambdaQuery()
                .in(MergeRequestEntity::getProjectRepositoryId, repoIds)
                .eq(status != null && !status.isBlank(), MergeRequestEntity::getStatus, status)
                .eq(repositoryId != null, MergeRequestEntity::getProjectRepositoryId, repositoryId)
                .lt(cursorUuid != null, MergeRequestEntity::getId, cursorUuid)
                .orderByDesc(MergeRequestEntity::getId)
                .last("LIMIT " + (size + 1));
        if (groupId != null) {
            List<UUID> mrIds = mergeRequestGroupMapper.selectByRequirementGroupId(groupId).stream()
                    .map(MergeRequestGroupEntity::getMergeRequestId).toList();
            if (mrIds.isEmpty()) {
                return emptyPage(requestId);
            }
            query.in(MergeRequestEntity::getId, mrIds);
        }
        List<MergeRequestEntity> rows = mergeRequestMapper.selectList(query);
        boolean hasMore = rows.size() > size;
        List<MergeRequestEntity> page = hasMore ? rows.subList(0, size) : rows;
        Map<UUID, List<String>> groupIdsByMr = groupIdsByMr(page);
        List<MergeRequestSummaryResponse> items = page.stream()
                .map(mr -> toSummary(mr, groupIdsByMr.getOrDefault(mr.getId(), List.of()), qualityGate(mr)))
                .toList();
        PageMeta meta = new PageMeta(hasMore ? items.get(items.size() - 1).getId() : null, hasMore);
        return new ApiPageResponse<>(items, meta, requestId);
    }

    /**
     * 查询 MR、关联需求群、检查与审查摘要，并汇总 qualityGate 状态。
     */
    public MergeRequestDetailResponse detail(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        List<String> groupIds = groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of());
        return toDetail(mr, groupIds, qualityGate(mr));
    }

    /**
     * Creates an MR mirror from a Task Workspace repository branch and committed
     * head.
     * 创建为本地镜像（OPEN），真实 GitHub PR 创建由 sync 接缝闭环。
     */
    @Transactional
    public MergeRequestSummaryResponse create(UUID projectId, UUID userId, MergeRequestCreateRequest request) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskEntity task = taskMapper.selectById(request.getTaskId());
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task does not exist or is not visible");
        }
        if (!userId.equals(task.getCreatedBy())) {
            projectAccess.requireProjectAdmin(projectId, userId);
        }
        ProjectRepositoryEntity repository = projectRepositoryMapper.selectById(request.getRepositoryId());
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPOSITORY_NOT_IN_PROJECT",
                    "Repository is not bound to the current Project");
        }
        WorkspaceRepositoryEntity worktree = workspaceRepositoryMapper.selectForUpdate(task.getWorkspaceId(),
                request.getRepositoryId());
        if (worktree == null || worktree.getHeadCommit() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_BRANCH_NOT_PUSHED",
                    "The repository branch must have a committed head before MR creation");
        }
        // TODO 接缝：接入 GitHub 前无法校验源分支是否仍存在；创建后由 sync 以真实 PR 号回写
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(UuidV7.next());
        mr.setProjectRepositoryId(request.getRepositoryId());
        mr.setTaskId(task.getId());
        mr.setWorkspaceId(task.getWorkspaceId());
        mr.setProvider("GITHUB");
        mr.setProviderNumber(nextProviderNumber(request.getRepositoryId()));
        mr.setSourceBranch(worktree.getSourceBranch());
        mr.setTargetBranch(request.getTargetBranch());
        mr.setHeadCommit(worktree.getHeadCommit());
        mr.setTitle(request.getTitle());
        mr.setStatus("OPEN");
        mr.setQualityGateStatus("PENDING");
        mr.setSyncedAt(now);
        mr.setAuthorUserId(userId);
        mr.setCreatedAt(now);
        mergeRequestMapper.insert(mr);
        if (task.getRequirementGroupId() != null) {
            MergeRequestGroupEntity relation = new MergeRequestGroupEntity();
            relation.setMergeRequestId(mr.getId());
            relation.setRequirementGroupId(task.getRequirementGroupId());
            mergeRequestGroupMapper.insert(relation);
        }
        refreshQualityGate(mr);
        publishUpdated(mr);
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr));
    }

    /**
     * 查询门禁检查详情。
     */
    public List<MergeRequestCheckResponse> checks(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        requireMr(projectId, mergeRequestId);
        return qualityCheckMapper.selectList(Wrappers.<QualityCheckResultEntity>lambdaQuery()
                .eq(QualityCheckResultEntity::getMergeRequestId, mergeRequestId)
                .orderByAsc(QualityCheckResultEntity::getCreatedAt)).stream().map(this::toCheck).toList();
    }

    /**
     * 查询人工与 AI 审查摘要。
     */
    public List<MergeRequestReviewResponse> reviews(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        requireMr(projectId, mergeRequestId);
        return reviewMapper.selectList(Wrappers.<MergeRequestReviewEntity>lambdaQuery()
                .eq(MergeRequestReviewEntity::getMergeRequestId, mergeRequestId)
                .orderByAsc(MergeRequestReviewEntity::getCreatedAt)).stream().map(this::toReview).toList();
    }

    /**
     * 触发从 GitHub 同步 MR 最新状态（202 接缝，真实拉取由外部集成服务承担）。
     */
    @Transactional
    public MergeRequestSummaryResponse sync(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        // TODO 接缝：调用 GitHub 集成服务拉取最新 PR 状态（状态、提交、检查与审查），
        // 并用真实 PR 号回写 providerNumber；接入前仅刷新本地同步时间并发布事件
        mr.setSyncedAt(LocalDateTime.now(ZoneOffset.UTC));
        mergeRequestMapper.updateById(mr);
        refreshQualityGate(mr);
        publishUpdated(mr);
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr));
    }

    /**
     * 提交一次 CQ+1 审查。
     * The MR author cannot review their own MR.
     */
    @Transactional
    public MergeRequestSummaryResponse cqApproval(UUID projectId, UUID mergeRequestId, UUID userId, String reason) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        requireCqReviewer(mr, userId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MergeRequestReviewEntity review = new MergeRequestReviewEntity();
        review.setId(UuidV7.next());
        review.setMergeRequestId(mr.getId());
        review.setReviewKind("HUMAN");
        review.setReviewerUserId(userId);
        review.setDecision("APPROVED");
        review.setSummary(reason);
        review.setReviewedAt(now);
        review.setCreatedAt(now);
        reviewMapper.insert(review);
        writeCheck(mr, "CQ_PLUS_ONE", "PASSED", "cq_approval", reason, now);
        refreshQualityGate(mr);
        publishUpdated(mr);
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr));
    }

    /**
     * 拒绝 CQ 并给出修改意见。
     * The MR author cannot reject their own MR.
     */
    @Transactional
    public MergeRequestSummaryResponse cqRejection(UUID projectId, UUID mergeRequestId, UUID userId, String reason) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        requireCqReviewer(mr, userId);
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CQ_REJECTION_REASON_REQUIRED", "拒绝 CQ 必须给出修改意见");
        }
        writeCheck(mr, "CQ_PLUS_ONE", "FAILED", "cq_rejection", reason, LocalDateTime.now(ZoneOffset.UTC));
        refreshQualityGate(mr);
        publishUpdated(mr);
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr));
    }

    /**
     * 通过质量门禁后执行合并（需 Project Admin）。
     * 门禁未通过时返回 409 QUALITY_GATE_NOT_PASSED；真实 GitHub 合并由接缝服务承担。
     */
    @Transactional
    public MergeRequestSummaryResponse merge(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectAdmin(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        if (!"PASSED".equals(qualityGate(mr).getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "QUALITY_GATE_NOT_PASSED", "质量门禁未通过，无法合并");
        }
        // TODO 接缝：调用 GitHub 集成服务执行真实合并；接入前 MR 保持 OPEN，不做伪合并
        publishUpdated(mr);
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr));
    }

    // ---------- 私有辅助 ----------

    /** 加载 MR 并校验其仓库属于路径项目，禁止仅凭 UUID 跨项目查询。 */
    private MergeRequestEntity requireMr(UUID projectId, UUID mergeRequestId) {
        MergeRequestEntity mr = mergeRequestMapper.selectById(mergeRequestId);
        if (mr == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        ProjectRepositoryEntity repo = projectRepositoryMapper.selectById(mr.getProjectRepositoryId());
        if (repo == null || !repo.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        return mr;
    }

    /** CQ reviewer must differ from the MR author. */
    private void requireCqReviewer(MergeRequestEntity mr, UUID userId) {
        if (userId.equals(mr.getAuthorUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CQ_REVIEWER_NOT_ALLOWED", "MR 作者不能审查自己的 CQ");
        }
    }

    /** 写入质量门禁检查结果（attemptNo 在同提交同类型内递增）。 */
    private void writeCheck(MergeRequestEntity mr, String checkType, String status, String source, String reason,
            LocalDateTime now) {
        QualityCheckResultEntity check = new QualityCheckResultEntity();
        check.setId(UuidV7.next());
        check.setMergeRequestId(mr.getId());
        check.setCheckType(checkType);
        check.setAttemptNo(nextAttemptNo(mr.getId(), checkType, mr.getHeadCommit()));
        check.setStatus(status);
        check.setCommitSha(mr.getHeadCommit());
        check.setSource(source);
        check.setSummary(reason == null ? Map.of() : Map.of("reason", reason));
        check.setStartedAt(now);
        check.setCompletedAt(now);
        check.setCreatedAt(now);
        qualityCheckMapper.insert(check);
    }

    /** 汇总目标分支质量门禁：必检项全部 PASSED → PASSED；任一 FAILED → FAILED；缺失/运行中 → PENDING。 */
    private QualityGateResponse qualityGate(MergeRequestEntity mr) {
        RepositoryBranchConfigEntity config = branchConfigMapper.selectOne(
                Wrappers.<RepositoryBranchConfigEntity>lambdaQuery()
                        .eq(RepositoryBranchConfigEntity::getProjectRepositoryId, mr.getProjectRepositoryId())
                        .eq(RepositoryBranchConfigEntity::getBranchName, mr.getTargetBranch()));
        List<String> required = new ArrayList<>();
        if (config != null && config.getRequiredChecks() != null) {
            required.addAll(config.getRequiredChecks());
        }
        List<UUID> requiredTestsets = config == null ? List.of()
                : branchConfigTestsetMapper.selectByBranchConfigId(config.getId()).stream()
                        .map(RepositoryBranchConfigTestsetEntity::getTestsetId).toList();
        if (!requiredTestsets.isEmpty() && !required.contains("TESTSET")) {
            required.add("TESTSET");
        }
        List<String> checks = required.stream().distinct().toList();
        String status = computeGateStatus(mr, checks, requiredTestsets);
        return new QualityGateResponse(status, checks);
    }

    private String computeGateStatus(MergeRequestEntity mr, List<String> checks, List<UUID> requiredTestsets) {
        boolean anyFailed = false;
        int satisfied = 0;
        int total = requiredTestsets.size() + (int) checks.stream().filter(c -> !"TESTSET".equals(c)).count();
        if (total == 0) {
            return "PASSED";
        }
        for (UUID testsetId : requiredTestsets) {
            QualityCheckResultEntity r = latestCheck(mr.getId(), "TESTSET", mr.getHeadCommit(), testsetId);
            if (r == null) {
                continue;
            }
            if ("FAILED".equals(r.getStatus())) {
                anyFailed = true;
            } else if ("PASSED".equals(r.getStatus())) {
                satisfied++;
            }
        }
        for (String check : checks) {
            if ("TESTSET".equals(check)) {
                continue;
            }
            QualityCheckResultEntity r = latestCheck(mr.getId(), check, mr.getHeadCommit(), null);
            if (r == null) {
                continue;
            }
            if ("FAILED".equals(r.getStatus())) {
                anyFailed = true;
            } else if ("PASSED".equals(r.getStatus())) {
                satisfied++;
            }
        }
        if (anyFailed) {
            return "FAILED";
        }
        return satisfied >= total ? "PASSED" : "PENDING";
    }

    /** 取 (mrId, checkType, commitSha[, testsetId]) 的最新 attempt_no 检查结果。 */
    private QualityCheckResultEntity latestCheck(UUID mrId, String checkType, String commitSha, UUID testsetId) {
        return qualityCheckMapper.selectOne(Wrappers.<QualityCheckResultEntity>lambdaQuery()
                .eq(QualityCheckResultEntity::getMergeRequestId, mrId)
                .eq(QualityCheckResultEntity::getCheckType, checkType)
                .eq(QualityCheckResultEntity::getCommitSha, commitSha)
                .eq(testsetId != null, QualityCheckResultEntity::getTestsetId, testsetId)
                .orderByDesc(QualityCheckResultEntity::getAttemptNo)
                .last("LIMIT 1"));
    }

    private int nextAttemptNo(UUID mrId, String checkType, String commitSha) {
        QualityCheckResultEntity last = latestCheck(mrId, checkType, commitSha, null);
        return (last == null || last.getAttemptNo() == null) ? 1 : last.getAttemptNo() + 1;
    }

    /** 重算并持久化 MR 的门禁汇总状态。 */
    private void refreshQualityGate(MergeRequestEntity mr) {
        String status = qualityGate(mr).getStatus();
        if (!status.equals(mr.getQualityGateStatus())) {
            mr.setQualityGateStatus(status);
            mergeRequestMapper.updateById(mr);
        }
    }

    /** 仓库内本地合成编号：无 GitHub 集成前递增占位，sync 接缝将用真实 PR 号回写。 */
    private long nextProviderNumber(UUID repositoryId) {
        MergeRequestEntity last = mergeRequestMapper.selectOne(Wrappers.<MergeRequestEntity>lambdaQuery()
                .eq(MergeRequestEntity::getProjectRepositoryId, repositoryId)
                .orderByDesc(MergeRequestEntity::getProviderNumber)
                .last("LIMIT 1"));
        return (last == null || last.getProviderNumber() == null) ? 1L : last.getProviderNumber() + 1L;
    }

    /** 批量取 MR 的需求群ID映射，避免列表 N+1 查询。 */
    private Map<UUID, List<String>> groupIdsByMr(List<MergeRequestEntity> mrs) {
        if (mrs.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = mrs.stream().map(MergeRequestEntity::getId).toList();
        return mergeRequestGroupMapper.selectByMergeRequestIds(ids).stream().collect(Collectors.groupingBy(
                MergeRequestGroupEntity::getMergeRequestId,
                Collectors.mapping(r -> id(r.getRequirementGroupId()), Collectors.toList())));
    }

    private void publishUpdated(MergeRequestEntity mr) {
        ProjectRepositoryEntity repo = projectRepositoryMapper.selectById(mr.getProjectRepositoryId());
        Map<String, Object> p = new HashMap<>();
        if (repo != null) {
            p.put("projectId", repo.getProjectId());
        }
        p.put("repositoryId", mr.getProjectRepositoryId());
        p.put("mergeRequestId", mr.getId());
        p.put("number", mr.getProviderNumber());
        p.put("status", mr.getStatus());
        p.put("qualityGateStatus", mr.getQualityGateStatus());
        p.put("sequence", 0);
        p.put("timestamp", Instant.now().toString());
        eventService.publish(repo == null ? null : repo.getProjectId(), null, "merge-request.updated",
                mr.getId().toString(), p);
    }

    private MergeRequestSummaryResponse toSummary(MergeRequestEntity mr, List<String> groupIds,
            QualityGateResponse gate) {
        return new MergeRequestSummaryResponse(id(mr.getId()), id(mr.getProjectRepositoryId()), groupIds,
                mr.getProvider(), mr.getProviderNumber(), mr.getSourceBranch(), mr.getTargetBranch(), mr.getStatus(),
                mr.getHeadCommit(), gate, mr.getTitle(), iso(mr.getCreatedAt()));
    }

    private MergeRequestDetailResponse toDetail(MergeRequestEntity mr, List<String> groupIds,
            QualityGateResponse gate) {
        return new MergeRequestDetailResponse(id(mr.getId()), id(mr.getProjectRepositoryId()), groupIds,
                mr.getProvider(), mr.getProviderNumber(), mr.getSourceBranch(), mr.getTargetBranch(), mr.getStatus(),
                mr.getHeadCommit(), mr.getTitle(), gate, id(mr.getAuthorUserId()), iso(mr.getSyncedAt()),
                iso(mr.getCreatedAt()));
    }

    private MergeRequestCheckResponse toCheck(QualityCheckResultEntity c) {
        return new MergeRequestCheckResponse(id(c.getId()), c.getCheckType(), c.getStatus(), c.getAttemptNo(),
                id(c.getTestsetId()), c.getCommitSha(), c.getSource(), c.getSummary(), iso(c.getStartedAt()),
                iso(c.getCompletedAt()));
    }

    private MergeRequestReviewResponse toReview(MergeRequestReviewEntity r) {
        return new MergeRequestReviewResponse(id(r.getId()), r.getReviewKind(), id(r.getReviewerUserId()),
                r.getReviewerName(), r.getDecision(), r.getSummary(), iso(r.getReviewedAt()));
    }

    private ApiPageResponse<MergeRequestSummaryResponse> emptyPage(String requestId) {
        return new ApiPageResponse<>(List.of(), new PageMeta(null, false), requestId);
    }

    private UUID parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "游标格式不合法");
        }
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    private String id(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }
}
