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
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.MergeRequestGroupEntity;
import qg.qgent.entity.MergeRequestReviewEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.QualityCheckResultEntity;
import qg.qgent.entity.RepositoryBranchConfigEntity;
import qg.qgent.entity.RepositoryBranchConfigTestsetEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.MergeRequestGroupMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.MergeRequestReviewMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.QualityCheckResultMapper;
import qg.qgent.mapper.RepositoryBranchConfigMapper;
import qg.qgent.mapper.RepositoryBranchConfigTestsetMapper;
import qg.qgent.github.GitHubAppClient;
import qg.qgent.github.GitHubPullRequestCreateRequest;
import qg.qgent.github.GitHubPullRequestDetails;
import qg.qgent.github.GitHubPullRequestMergeRequest;
import qg.qgent.github.GitHubPullRequestMergeResult;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitPushRequest;
import qg.qgent.orchestration.worker.WorkerGitPushResponse;
import qg.qgent.entity.GitCredentialPurpose;

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
    private final GitHubInstallationMapper githubInstallationMapper;
    private final GitHubRepositoryMapper githubRepositoryMapper;
    private final ProjectMapper projectMapper;
    private final GitHubAppClient githubClient;
    private final ProjectAccessService projectAccess;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final SandboxWorkerClient workerClient;
    private final GitCredentialService credentialService;

    public MergeRequestService(MergeRequestMapper mergeRequestMapper, MergeRequestGroupMapper mergeRequestGroupMapper,
            QualityCheckResultMapper qualityCheckMapper, MergeRequestReviewMapper reviewMapper,
            TaskMapper taskMapper, WorkspaceRepositoryMapper workspaceRepositoryMapper,
            ProjectRepositoryMapper projectRepositoryMapper,
            RepositoryBranchConfigMapper branchConfigMapper,
            RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper, ProjectAccessService projectAccess,
            EventService eventService, GitHubInstallationMapper githubInstallationMapper,
            GitHubRepositoryMapper githubRepositoryMapper, ProjectMapper projectMapper, GitHubAppClient githubClient,
            NotificationService notificationService, SandboxWorkerClient workerClient,
            GitCredentialService credentialService) {
        this.mergeRequestMapper = mergeRequestMapper;
        this.mergeRequestGroupMapper = mergeRequestGroupMapper;
        this.qualityCheckMapper = qualityCheckMapper;
        this.reviewMapper = reviewMapper;
        this.taskMapper = taskMapper;
        this.workspaceRepositoryMapper = workspaceRepositoryMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.branchConfigMapper = branchConfigMapper;
        this.branchConfigTestsetMapper = branchConfigTestsetMapper;
        this.githubInstallationMapper = githubInstallationMapper;
        this.githubRepositoryMapper = githubRepositoryMapper;
        this.projectMapper = projectMapper;
        this.githubClient = githubClient;
        this.projectAccess = projectAccess;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.workerClient = workerClient;
        this.credentialService = credentialService;
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
        Map<UUID, QualityGateResponse> gatesByMr = qualityGates(page);
        List<MergeRequestSummaryResponse> items = page.stream()
                .map(mr -> toSummary(mr, groupIdsByMr.getOrDefault(mr.getId(), List.of()), gatesByMr.get(mr.getId())))
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

    /** Creates a local mirror only after GitHub has created the real Pull Request. */
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
        GitHubRepositoryEntity githubRepository = requireGitHubRepository(projectId, request.getRepositoryId());
        GitHubInstallationEntity installation = githubInstallationMapper.selectById(githubRepository.getInstallationId());
        if (installation == null || !"ACTIVE".equalsIgnoreCase(installation.getStatus())
                || installation.getProviderInstallationId() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_INSTALLATION_UNAVAILABLE",
                    "No active GitHub App installation is available for this repository");
        }

        MergeRequestEntity existing = mergeRequestMapper.selectOne(Wrappers.<MergeRequestEntity>lambdaQuery()
                .eq(MergeRequestEntity::getProjectRepositoryId, request.getRepositoryId())
                .eq(MergeRequestEntity::getSourceBranch, worktree.getSourceBranch())
                .eq(MergeRequestEntity::getTargetBranch, request.getTargetBranch())
                .eq(MergeRequestEntity::getStatus, "OPEN")
                .orderByDesc(MergeRequestEntity::getCreatedAt)
                .last("LIMIT 1"));
        if (existing != null) {
            if (worktree.getHeadCommit().equals(existing.getHeadCommit())) {
                return toSummary(existing, groupIdsByMr(List.of(existing)).getOrDefault(existing.getId(), List.of()),
                        qualityGate(existing));
            }
            throw new ApiException(HttpStatus.CONFLICT, "OPEN_MR_ALREADY_EXISTS",
                    "An open Pull Request already exists for this source and target branch");
        }

        String repositoryFullName = githubRepository.getOwnerLogin() + "/" + githubRepository.getName();
        // Recover an earlier successful GitHub create when the local transaction
        // failed after the remote call. This makes retries idempotent across systems.
        GitHubPullRequestDetails remote = githubClient.findOpenPullRequest(
                installation.getProviderInstallationId(), githubRepository.getOwnerLogin(), githubRepository.getName(),
                worktree.getSourceBranch(), request.getTargetBranch());
        if (remote == null) {
            String grantId = credentialService.generateGrant(installation.getTeamId(), projectId,
                    installation.getProviderInstallationId(), repositoryFullName, worktree.getSourceBranch(),
                    worktree.getHeadCommit(), GitCredentialPurpose.PUSH);

            WorkerGitPushResponse pushResponse;
            try {
                pushResponse = workerClient.pushWorkspaceBranch(task.getWorkspaceId(), request.getRepositoryId(),
                        new WorkerGitPushRequest().setExpectedHeadCommit(worktree.getHeadCommit())
                                .setCredentialGrantId(grantId));
            } catch (ApiException e) {
                throw new ApiException(e.status(), "WORKER_PUSH_FAILED",
                        "Failed to push branch via Sandbox Worker: " + e.getMessage());
            }

            if (!pushResponse.isVerified() || !worktree.getHeadCommit().equals(pushResponse.getHeadCommit())) {
                throw new ApiException(HttpStatus.CONFLICT, "WORKER_PUSH_VERIFICATION_FAILED",
                        "Sandbox Worker push verification failed or HEAD mismatch");
            }

            remote = githubClient.createPullRequest(
                    installation.getProviderInstallationId(), githubRepository.getOwnerLogin(), githubRepository.getName(),
                    new GitHubPullRequestCreateRequest(request.getTitle(), null, worktree.getSourceBranch(),
                            request.getTargetBranch()));
        }
        if (remote == null || remote.number() <= 0 || remote.headSha() == null
                || !worktree.getHeadCommit().equalsIgnoreCase(remote.headSha())
                || !worktree.getSourceBranch().equals(remote.headBranch())
                || !request.getTargetBranch().equals(remote.baseBranch())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_PR_RESPONSE_INVALID",
                    "GitHub returned an invalid Pull Request response");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MergeRequestEntity mr = new MergeRequestEntity();
        mr.setId(UuidV7.next());
        mr.setProjectRepositoryId(request.getRepositoryId());
        mr.setTaskId(task.getId());
        mr.setWorkspaceId(task.getWorkspaceId());
        mr.setProvider("GITHUB");
        mr.setProviderNumber((long) remote.number());
        mr.setSourceBranch(remote.headBranch());
        mr.setTargetBranch(remote.baseBranch());
        mr.setHeadCommit(remote.headSha());
        mr.setTitle(remote.title() == null ? request.getTitle() : remote.title());
        mr.setStatus(toLocalStatus(remote));
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

    /** Refreshes the local mirror from GitHub's current Pull Request state. */
    @Transactional
    public MergeRequestSummaryResponse sync(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        GitHubRepositoryEntity githubRepository = requireGitHubRepository(projectId, mr.getProjectRepositoryId());
        GitHubInstallationEntity installation = requireInstallation(githubRepository);
        GitHubPullRequestDetails remote = githubClient.getPullRequest(installation.getProviderInstallationId(),
                githubRepository.getOwnerLogin(), githubRepository.getName(), requireProviderNumber(mr));
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        mr.setProviderNumber((long) remote.number());
        mr.setSourceBranch(remote.headBranch());
        mr.setTargetBranch(remote.baseBranch());
        mr.setHeadCommit(remote.headSha());
        if (remote.title() != null) {
            mr.setTitle(remote.title());
        }
        mr.setStatus(toLocalStatus(remote));
        mr.setProviderUpdatedAt(now);
        mr.setSyncedAt(now);
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

    /** Requests a real GitHub merge after local quality gates pass. */
    @Transactional
    public MergeRequestSummaryResponse merge(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectAdmin(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        if (!"OPEN".equals(mr.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_REQUEST_NOT_OPEN",
                    "Only an open Pull Request can be merged");
        }
        if (!"PASSED".equals(qualityGate(mr).getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "QUALITY_GATE_NOT_PASSED", "质量门禁未通过，无法合并");
        }
        GitHubRepositoryEntity githubRepository = requireGitHubRepository(projectId, mr.getProjectRepositoryId());
        GitHubInstallationEntity installation = requireInstallation(githubRepository);
        GitHubPullRequestMergeResult result = githubClient.mergePullRequest(installation.getProviderInstallationId(),
                githubRepository.getOwnerLogin(), githubRepository.getName(), requireProviderNumber(mr),
                new GitHubPullRequestMergeRequest("Merge " + (mr.getTitle() == null ? "Pull Request" : mr.getTitle()),
                        null, "squash", mr.getHeadCommit()));
        if (!result.merged()) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_MERGE_NOT_COMPLETED",
                    result.message() == null ? "GitHub did not merge the Pull Request" : result.message());
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        mr.setStatus("MERGED");
        mr.setProviderUpdatedAt(now);
        mr.setSyncedAt(now);
        mergeRequestMapper.updateById(mr);
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
        return qualityGates(List.of(mr)).get(mr.getId());
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

    /** 批量汇总多个 MR 的目标分支质量门禁状态，消除 N+1 查询 */
    private Map<UUID, QualityGateResponse> qualityGates(List<MergeRequestEntity> mrs) {
        if (mrs.isEmpty()) {
            return Map.of();
        }
        
        List<UUID> repoIds = mrs.stream().map(MergeRequestEntity::getProjectRepositoryId).distinct().toList();
        List<RepositoryBranchConfigEntity> allConfigs = branchConfigMapper.selectList(
                Wrappers.<RepositoryBranchConfigEntity>query()
                        .in("project_repository_id", repoIds));
                        
        Map<UUID, RepositoryBranchConfigEntity> mrConfigMap = new HashMap<>();
        for (MergeRequestEntity mr : mrs) {
            allConfigs.stream()
                    .filter(c -> java.util.Objects.equals(c.getProjectRepositoryId(), mr.getProjectRepositoryId()) && java.util.Objects.equals(c.getBranchName(), mr.getTargetBranch()))
                    .findFirst()
                    .ifPresent(c -> mrConfigMap.put(mr.getId(), c));
        }

        List<UUID> configIds = mrConfigMap.values().stream().map(RepositoryBranchConfigEntity::getId).distinct().toList();
        List<RepositoryBranchConfigTestsetEntity> allTestsets = configIds.isEmpty() ? List.of() :
                branchConfigTestsetMapper.selectList(Wrappers.<RepositoryBranchConfigTestsetEntity>query()
                        .in("branch_config_id", configIds));
                        
        Map<UUID, List<UUID>> configTestsetsMap = allTestsets.stream()
                .collect(Collectors.groupingBy(RepositoryBranchConfigTestsetEntity::getBranchConfigId,
                        Collectors.mapping(RepositoryBranchConfigTestsetEntity::getTestsetId, Collectors.toList())));

        List<UUID> mrIds = mrs.stream().map(MergeRequestEntity::getId).toList();
        List<QualityCheckResultEntity> allChecks = qualityCheckMapper.selectList(
                Wrappers.<QualityCheckResultEntity>query()
                        .in("merge_request_id", mrIds)
                        .orderByDesc("attempt_no"));
                        
        Map<UUID, QualityGateResponse> resultMap = new HashMap<>();
        for (MergeRequestEntity mr : mrs) {
            RepositoryBranchConfigEntity config = mrConfigMap.get(mr.getId());
            List<String> required = new ArrayList<>();
            if (config != null && config.getRequiredChecks() != null) {
                required.addAll(config.getRequiredChecks());
            }
            List<UUID> requiredTestsets = config == null ? List.of() :
                    configTestsetsMap.getOrDefault(config.getId(), List.of());
                    
            if (!requiredTestsets.isEmpty() && !required.contains("TESTSET")) {
                required.add("TESTSET");
            }
            List<String> checks = required.stream().distinct().toList();
            
            List<QualityCheckResultEntity> mrChecks = allChecks.stream()
                    .filter(c -> java.util.Objects.equals(c.getMergeRequestId(), mr.getId()) && java.util.Objects.equals(c.getCommitSha(), mr.getHeadCommit()))
                    .toList();
                    
            String status = computeGateStatusFromList(mrChecks, checks, requiredTestsets);
            resultMap.put(mr.getId(), new QualityGateResponse(status, checks));
        }
        return resultMap;
    }

    private String computeGateStatusFromList(List<QualityCheckResultEntity> mrChecks, List<String> checks, List<UUID> requiredTestsets) {
        boolean anyFailed = false;
        int satisfied = 0;
        int total = requiredTestsets.size() + (int) checks.stream().filter(c -> !"TESTSET".equals(c)).count();
        if (total == 0) {
            return "PASSED";
        }
        for (UUID testsetId : requiredTestsets) {
            QualityCheckResultEntity r = mrChecks.stream()
                    .filter(c -> "TESTSET".equals(c.getCheckType()) && testsetId.equals(c.getTestsetId()))
                    .findFirst().orElse(null);
            if (r == null) continue;
            if ("FAILED".equals(r.getStatus())) anyFailed = true;
            else if ("PASSED".equals(r.getStatus())) satisfied++;
        }
        for (String check : checks) {
            if ("TESTSET".equals(check)) continue;
            QualityCheckResultEntity r = mrChecks.stream()
                    .filter(c -> check.equals(c.getCheckType()) && c.getTestsetId() == null)
                    .findFirst().orElse(null);
            if (r == null) continue;
            if ("FAILED".equals(r.getStatus())) anyFailed = true;
            else if ("PASSED".equals(r.getStatus())) satisfied++;
        }
        if (anyFailed) return "FAILED";
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

    private GitHubRepositoryEntity requireGitHubRepository(UUID projectId, UUID projectRepositoryId) {
        ProjectRepositoryEntity binding = projectRepositoryMapper.selectById(projectRepositoryId);
        if (binding == null || !projectId.equals(binding.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "Repository does not exist or is not visible");
        }
        GitHubRepositoryEntity repository = githubRepositoryMapper.selectById(binding.getRepositoryId());
        if (repository == null || Boolean.TRUE.equals(repository.getArchived())) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_REPOSITORY_UNAVAILABLE",
                    "The bound GitHub repository is unavailable or archived");
        }
        var project = projectMapper.selectById(projectId);
        GitHubInstallationEntity installation = repository.getInstallationId() == null ? null
                : githubInstallationMapper.selectById(repository.getInstallationId());
        if (project == null || installation == null || !project.getTeamId().equals(installation.getTeamId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "GITHUB_REPOSITORY_NOT_AUTHORIZED",
                    "The GitHub repository installation is not authorized for this project");
        }
        return repository;
    }

    private GitHubInstallationEntity requireInstallation(GitHubRepositoryEntity repository) {
        GitHubInstallationEntity installation = githubInstallationMapper.selectById(repository.getInstallationId());
        if (installation == null || !"ACTIVE".equalsIgnoreCase(installation.getStatus())
                || installation.getProviderInstallationId() == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_INSTALLATION_UNAVAILABLE",
                    "No active GitHub App installation is available for this repository");
        }
        return installation;
    }

    private int requireProviderNumber(MergeRequestEntity mr) {
        if (mr.getProviderNumber() == null || mr.getProviderNumber() <= 0 || mr.getProviderNumber() > Integer.MAX_VALUE) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_PR_NUMBER_MISSING", "The MR has no valid GitHub Pull Request number");
        }
        return mr.getProviderNumber().intValue();
    }

    private String toLocalStatus(GitHubPullRequestDetails remote) {
        if (remote.merged()) {
            return "MERGED";
        }
        return "open".equalsIgnoreCase(remote.state()) ? "OPEN" : "CLOSED";
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
        notifyMrPending(mr, repo == null ? null : repo.getProjectId());
    }

    /**
     * MR 状态更新后向任务发起人写入通知（A 联调约定 §1）。
     * MR 未关联任务或发起人缺失时静默跳过，不阻断 MR 同步。
     */
    private void notifyMrPending(MergeRequestEntity mr, UUID projectId) {
        if (mr.getTaskId() == null) {
            return;
        }
        TaskEntity task = taskMapper.selectById(mr.getTaskId());
        if (task == null) {
            return;
        }
        notificationService.notify(task.getCreatedBy(), projectId, task.getRequirementGroupId(), "MR_PENDING",
                "MR 状态更新：" + (mr.getTitle() == null || mr.getTitle().isBlank() ? mr.getProviderNumber() : mr.getTitle()),
                mr.getStatus(), mr.getId().toString());
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
