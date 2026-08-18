package qg.qgent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.github.*;
import qg.qgent.mapper.*;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitPushRequest;
import qg.qgent.orchestration.worker.WorkerGitPushResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.function.Function;

/**
 * MR 镜像、审查与质量门禁服务。
 * MR belongs to one repository and is derived from the persisted source branch
 * and head commit
 * of a Task Workspace. Client-supplied credentials, commit SHAs and gate
 * outcomes are not trusted.
 * qualityGate 汇总：从目标分支 branch config 的 MR 后 required_checks 取必检项，
 * 对照 quality_check_results 在 headCommit 的最新 attempt_no；全部 PASSED → PASSED，
 * 任一 FAILED → FAILED，缺失或运行中 → PENDING。
 * 目标分支绑定 Testset 的真实结果由 MR 前 Dry Run 固定并由 {@link PreflightGateService} 校验，
 * 不重复要求不存在的 MR 级 {@code TESTSET} quality_check_results。
 */
@Service
public class MergeRequestService {
    private static final Logger log = LoggerFactory.getLogger(MergeRequestService.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final Duration MERGE_OPERATION_LEASE = Duration.ofMinutes(20);
    /**
     * 创建 MR 后轮询 GitHub mergeable 的最大次数与间隔；GitHub 通常在数秒内算完，
     * 命中非 null 即提前返回。
     */
    private static final int MERGEABLE_POLL_ATTEMPTS = 5;
    private static final Duration MERGEABLE_POLL_INTERVAL = Duration.ofSeconds(2);

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
    private final MergeRequestDeliveryOperationMapper deliveryOperationMapper;
    private final TransactionTemplate transactions;
    private final DiffMapper diffMapper;
    /**
     * PR 创建成功后的检查写入/通知钩子。@Autowired setter 注入：避免主构造器继续膨胀，
     * 也保持既有纯 Mockito 测试构造器兼容（未注入时钩子静默跳过）。
     */
    private MrQualityGateService qualityGates;
    /** P1 MR 前预检门禁。缺失时必须拒绝创建 MR，不能降级为绕过门禁。 */
    private PreflightGateService preflightGates;
    /** 已确认创建真实 MR 后的群聊回卡依赖；发送失败不得改变远端 MR 事实。 */
    private MessageService messageService;
    private OrchestratorAgentService orchestratorAgents;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setQualityGates(MrQualityGateService qualityGates) {
        this.qualityGates = qualityGates;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setPreflightGates(PreflightGateService preflightGates) {
        this.preflightGates = preflightGates;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMessageService(MessageService messageService) {
        this.messageService = messageService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOrchestratorAgents(OrchestratorAgentService orchestratorAgents) {
        this.orchestratorAgents = orchestratorAgents;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public MergeRequestService(MergeRequestMapper mergeRequestMapper, MergeRequestGroupMapper mergeRequestGroupMapper,
                               QualityCheckResultMapper qualityCheckMapper, MergeRequestReviewMapper reviewMapper,
                               TaskMapper taskMapper, WorkspaceRepositoryMapper workspaceRepositoryMapper,
                               ProjectRepositoryMapper projectRepositoryMapper,
                               RepositoryBranchConfigMapper branchConfigMapper,
                               RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper, ProjectAccessService projectAccess,
                               EventService eventService, GitHubInstallationMapper githubInstallationMapper,
                               GitHubRepositoryMapper githubRepositoryMapper, ProjectMapper projectMapper, GitHubAppClient githubClient,
                               NotificationService notificationService, SandboxWorkerClient workerClient,
                               GitCredentialService credentialService, MergeRequestDeliveryOperationMapper deliveryOperationMapper,
                               TransactionTemplate transactions, DiffMapper diffMapper) {
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
        this.deliveryOperationMapper = deliveryOperationMapper;
        this.transactions = transactions;
        this.diffMapper = diffMapper;
    }

    /**
     * 兼容纯 Mockito 单元测试；生产环境始终使用带事务协调器的构造器。
     */
    MergeRequestService(MergeRequestMapper mergeRequestMapper, MergeRequestGroupMapper mergeRequestGroupMapper,
                        QualityCheckResultMapper qualityCheckMapper, MergeRequestReviewMapper reviewMapper,
                        TaskMapper taskMapper, WorkspaceRepositoryMapper workspaceRepositoryMapper,
                        ProjectRepositoryMapper projectRepositoryMapper, RepositoryBranchConfigMapper branchConfigMapper,
                        RepositoryBranchConfigTestsetMapper branchConfigTestsetMapper, ProjectAccessService projectAccess,
                        EventService eventService, GitHubInstallationMapper githubInstallationMapper,
                        GitHubRepositoryMapper githubRepositoryMapper, ProjectMapper projectMapper, GitHubAppClient githubClient,
                        NotificationService notificationService, SandboxWorkerClient workerClient,
                        GitCredentialService credentialService) {
        this(mergeRequestMapper, mergeRequestGroupMapper, qualityCheckMapper, reviewMapper, taskMapper,
                workspaceRepositoryMapper, projectRepositoryMapper, branchConfigMapper, branchConfigTestsetMapper,
                projectAccess, eventService, githubInstallationMapper, githubRepositoryMapper, projectMapper,
                githubClient, notificationService, workerClient, credentialService, null, null, null);
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
        Map<UUID, String> webUrlsByMr = webUrlsByMr(page);
        List<MergeRequestSummaryResponse> items = page.stream()
                .map(mr -> toSummary(mr, groupIdsByMr.getOrDefault(mr.getId(), List.of()),
                        gatesByMr.get(mr.getId()), webUrlsByMr.get(mr.getId())))
                .toList();
        PageMeta meta = new PageMeta(hasMore ? items.get(items.size() - 1).getId() : null, hasMore);
        return new ApiPageResponse<>(items, meta, requestId);
    }

    /**
     * 查询 MR、关联需求群、检查与审查摘要，并汇总 qualityGate 状态。
     * 额外返回 webUrl（GitHub 地址）、diffId（关联的已接受 Diff）、description（当前为 null）。
     */
    public MergeRequestDetailResponse detail(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        List<String> groupIds = groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of());
        return toDetail(mr, groupIds, qualityGate(mr), mrWebUrl(mr), acceptedDiffId(mr));
    }

    /**
     * 以短事务领取、无事务外调、短事务落库的方式创建真实 Pull Request。
     */
    public MergeRequestSummaryResponse create(UUID projectId, UUID userId, MergeRequestCreateRequest request) {
        projectAccess.requireProjectMember(projectId, userId);
        // 先鉴权再访问 GitHub/Worker，避免无项目权限的请求借由目标分支刷新探测仓库状态，
        // 或无谓消耗外部调用额度。
        // 目标分支的 SHA 必须在短数据库事务外解析；claimCreate 仅用该不可变值查询预检事实。
        PreflightGateService gates = requirePreflightGates();
        request.setTargetBranch(gates.normalizeTargetBranch(request.getTargetBranch()));
        String targetCommit = gates.resolveTargetCommit(projectId, request.getRepositoryId(), request.getTargetBranch());
        CreateClaim claim = claimCreateWithRetry(projectId, userId, request, targetCommit);
        CreateClaim resolvedClaim = reconcileExistingOpenMr(projectId, userId, request, targetCommit, claim);
        if (resolvedClaim.existing() != null) {
            if (resolvedClaim.existing().getHeadCommit().equals(resolvedClaim.worktree().getHeadCommit())) {
                // 远端 MR 已经精确对应当前已审核并推送的提交；这是安全的幂等重放。
                TaskEntity completedTask = inTransaction(() -> markMrCreatedAndCompleteTask(resolvedClaim));
                publishTaskCompleted(completedTask);
                publishMergeRequestCard(resolvedClaim.task(), resolvedClaim.existing());
                log.info("merge request push skipped projectId={} taskId={} repositoryId={} branch={} reason=existing_open_mr_same_head headCommit={} mrId={}",
                        projectId, request.getTaskId(), request.getRepositoryId(), resolvedClaim.worktree().getSourceBranch(),
                        resolvedClaim.worktree().getHeadCommit(), resolvedClaim.existing().getId());
                return summary(resolvedClaim.existing());
            }
            log.info("merge request push required projectId={} taskId={} repositoryId={} branch={} reason=existing_open_mr_head_changed expectedHeadCommit={} mrId={}",
                    projectId, request.getTaskId(), request.getRepositoryId(), resolvedClaim.worktree().getSourceBranch(),
                    resolvedClaim.worktree().getHeadCommit(), resolvedClaim.existing().getId());
            verifyRemoteCreationContext(gates, projectId, resolvedClaim, targetCommit);
            return pushAndUpdateExisting(resolvedClaim);
        }
        try {
            // GitHub 按分支名创建 PR。领取操作结束后，目标分支或同一 Workspace 的续作都可能
            // 已推进 source branch；此处必须复核，不能将旧 Dry Run/CQ 的结论用于新代码。
            verifyRemoteCreationContext(gates, projectId, resolvedClaim, targetCommit);
            GitHubPullRequestDetails remote = createRemote(resolvedClaim);
            validateRemote(resolvedClaim, remote);
            recordRemoteCreated(resolvedClaim, remote);
            CreateFinalization finalized = inTransaction(() -> finalizeCreate(resolvedClaim, remote));
            MergeRequestEntity mr = finalized.mergeRequest();
            publishTaskCompleted(finalized.completedTask());
            publishUpdated(mr);
            publishMergeRequestCard(resolvedClaim.task(), mr);
            MergeRequestEntity refreshed;
            try {
                // mergeability 轮询是 best-effort：MR 已创建成功，轮询失败不得影响交付结果。
                refreshed = pollMergeability(projectId, resolvedClaim.githubRepository(), resolvedClaim.installation(), mr);
            } catch (RuntimeException failure) {
                log.warn("mergeability poll failed for MR {}, falling back to created state", mr.getId(), failure);
                refreshed = mr;
            }
            // PR 创建成功后写入 AI_REVIEW 检查并发 MR_PENDING 通知（best-effort，
            // 失败不影响已创建的 PR 事实；DIFF_FIRST 手动建 MR 同样受益）
            if (qualityGates != null) {
                try {
                    qualityGates.onPullRequestCreated(refreshed);
                } catch (RuntimeException failure) {
                    log.warn("post-create quality gate hooks failed for MR {}", mr.getId(), failure);
                }
            }
            return summary(refreshed);
        } catch (RuntimeException failure) {
            markCreateFailed(resolvedClaim, failure);
            throw failure;
        }
    }

    /**
     * 将已确认、已提交的 Task Diff 推送到其受控 feature branch。
     * <p>
     * Push 是独立事实，不能借由创建 MR 间接完成；调用方须在事务外执行本方法，并仅在 Worker
     * 返回与当前 Workspace HEAD 一致的已核验 SHA 后将 Diff 标记为 {@code PUSHED}。
     */
    public void pushAcceptedBranch(UUID projectId, UUID taskId, UUID repositoryId) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task does not exist or is not visible");
        }
        WorkspaceRepositoryEntity worktree = workspaceRepositoryMapper.selectByWorkspace(task.getWorkspaceId()).stream()
                .filter(value -> repositoryId.equals(value.getProjectRepositoryId())).findFirst().orElse(null);
        if (worktree == null || worktree.getHeadCommit() == null || worktree.getSourceBranch() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_BRANCH_NOT_COMMITTED",
                    "The repository branch must have a committed head before it can be pushed");
        }
        requireAcceptedCommitForPush(task, worktree, repositoryId);
        GitHubRepositoryEntity github = requireGitHubRepository(projectId, repositoryId);
        GitHubInstallationEntity installation = requireInstallation(github);
        pushBranch(task, repositoryId, worktree, github, installation, "accepted_diff");
    }

    private CreateClaim claimCreateWithRetry(UUID projectId, UUID userId, MergeRequestCreateRequest request,
                                             String targetCommit) {
        try {
            return inTransaction(() -> claimCreate(projectId, userId, request, targetCommit));
        } catch (DuplicateKeyException race) {
            return inTransaction(() -> claimCreate(projectId, userId, request, targetCommit));
        }
    }

    private CreateClaim claimCreate(UUID projectId, UUID userId, MergeRequestCreateRequest request, String targetCommit) {
        TaskEntity task = taskMapper.selectById(request.getTaskId());
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task does not exist or is not visible");
        }
        if (!userId.equals(task.getCreatedBy())) projectAccess.requireProjectAdmin(projectId, userId);
        ProjectRepositoryEntity repository = projectRepositoryMapper.selectByIdForUpdate(request.getRepositoryId());
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
        requireAcceptedDelivery(task, worktree, request.getRepositoryId());
        MergeRequestEntity existing = mergeRequestMapper.selectOne(Wrappers.<MergeRequestEntity>lambdaQuery()
                .eq(MergeRequestEntity::getProjectRepositoryId, request.getRepositoryId())
                .eq(MergeRequestEntity::getSourceBranch, worktree.getSourceBranch())
                .eq(MergeRequestEntity::getTargetBranch, request.getTargetBranch())
                .eq(MergeRequestEntity::getStatus, "OPEN").orderByDesc(MergeRequestEntity::getCreatedAt)
                .last("LIMIT 1"));
        requireTaskReadyForMr(task, worktree);
        // 已完成的 MR_FIRST Task 重放本地 OPEN 镜像时，后续仍会以 GitHub 真实开放 PR 和
        // 相同 head 校验为准。不要因为目标分支后来推进而让已创建 MR 的幂等查询错误地要求
        // 重跑 Dry Run；如果远端实际上已关闭，reconcile 会关闭镜像并重新领取，此时必须走当前门禁。
        boolean completedReplayCandidate = "MR_FIRST".equals(task.getDeliveryMode())
                && "SUCCEEDED".equals(task.getStatus()) && existing != null
                && sameCommit(worktree.getHeadCommit(), existing.getHeadCommit());
        if (!completedReplayCandidate) {
            requirePreflightGates().requireReady(task, worktree, request.getRepositoryId(), request.getTargetBranch(), targetCommit);
        }
        // 仅在本地、任务状态与预检均通过后才取得 GitHub 上下文；后续仍必须查询远端核验
        // OPEN 镜像，不能把本地记录当作创建成功事实。
        GitHubRepositoryEntity githubRepository = requireGitHubRepository(projectId, request.getRepositoryId());
        GitHubInstallationEntity installation = requireInstallation(githubRepository);
        if (existing != null && worktree.getHeadCommit().equals(existing.getHeadCommit())) {
            return new CreateClaim(task, worktree, githubRepository, installation, request, null, null, existing);
        }
        if (existing != null) {
            // 已有 open MR 且 headCommit 不同：推送新 commit 后更新已有 MR，不新建 PR，也不走 delivery operation
            return new CreateClaim(task, worktree, githubRepository, installation, request, null, null, existing);
        }
        if (deliveryOperationMapper == null) {
            return new CreateClaim(task, worktree, githubRepository, installation, request, null, null, null);
        }
        MergeRequestDeliveryOperationEntity active = deliveryOperationMapper.selectActiveBranchForUpdate(
                request.getRepositoryId(), worktree.getSourceBranch(), request.getTargetBranch());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (active != null && active.getLeaseExpiresAt() != null && active.getLeaseExpiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_CREATION_IN_PROGRESS",
                    "Pull Request creation is already in progress for this branch");
        }
        if (active != null) {
            active.setStatus("FAILED");
            active.setFailureCode("MR_CREATION_LEASE_EXPIRED");
            active.setClaimToken(null);
            active.setLeaseExpiresAt(null);
            active.setUpdatedAt(now);
            deliveryOperationMapper.updateById(active);
        }
        String key = operationKey(projectId, task, worktree, request);
        MergeRequestDeliveryOperationEntity operation = deliveryOperationMapper.selectByKeyForUpdate(key);
        if (operation != null) requireOperationContext(operation, projectId, task, worktree, request);
        if (operation != null && "COMPLETED".equals(operation.getStatus())) {
            MergeRequestEntity completed = mergeRequestMapper.selectById(operation.getMergeRequestId());
            if (completed != null) return new CreateClaim(task, worktree, githubRepository, installation,
                    request, operation, null, completed);
        }
        if (operation != null && (("RUNNING".equals(operation.getStatus())
                || "REMOTE_CREATED".equals(operation.getStatus()))
                && operation.getLeaseExpiresAt() != null && operation.getLeaseExpiresAt().isAfter(now))) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_CREATION_IN_PROGRESS", "Pull Request creation is already in progress");
        }
        String token = UUID.randomUUID().toString();
        if (operation == null) {
            operation = new MergeRequestDeliveryOperationEntity();
            operation.setId(UuidV7.next());
            operation.setOperationKey(key);
            operation.setProjectId(projectId);
            operation.setProjectRepositoryId(request.getRepositoryId());
            operation.setTaskId(task.getId());
            operation.setWorkspaceId(task.getWorkspaceId());
            operation.setActorUserId(userId);
            operation.setSourceBranch(worktree.getSourceBranch());
            operation.setTargetBranch(request.getTargetBranch());
            operation.setHeadCommit(worktree.getHeadCommit());
            operation.setTitle(request.getTitle());
            operation.setCreatedAt(now);
        }
        operation.setStatus("RUNNING");
        operation.setClaimToken(token);
        operation.setLeaseExpiresAt(now.plus(Duration.ofMinutes(20)));
        operation.setFailureCode(null);
        operation.setUpdatedAt(now);
        if (deliveryOperationMapper.selectById(operation.getId()) == null) deliveryOperationMapper.insert(operation);
        else deliveryOperationMapper.updateById(operation);
        return new CreateClaim(task, worktree, githubRepository, installation, request, operation, token, null);
    }

    /**
     * 已有 open MR 且 headCommit 不同：推送新 commit 到已有分支（GitHub 会自动更新该 PR），
     * 并同步本地 MR 镜像的 headCommit，不新建 PR。
     */
    private MergeRequestSummaryResponse pushAndUpdateExisting(CreateClaim claim) {
        WorkspaceRepositoryEntity worktree = claim.worktree();
        // 当前 head 已由 requireAcceptedDelivery 证明为真实 PUSHED；不得在创建/更新 MR 时
        // 重新发起 Worker push，否则 Worker 的短暂不可用会阻塞本已成功推送的 MR 操作。
        MergeRequestEntity existing = claim.existing();
        existing.setHeadCommit(worktree.getHeadCommit());
        existing.setSyncedAt(LocalDateTime.now(ZoneOffset.UTC));
        mergeRequestMapper.updateById(existing);
        TaskEntity completedTask = inTransaction(() -> markMrCreatedAndCompleteTask(claim));
        publishTaskCompleted(completedTask);
        publishUpdated(existing);
        return summary(existing);
    }

    private GitHubPullRequestDetails createRemote(CreateClaim claim) {
        GitHubRepositoryEntity github = claim.githubRepository();
        GitHubInstallationEntity installation = claim.installation();
        WorkspaceRepositoryEntity worktree = claim.worktree();
        MergeRequestCreateRequest request = claim.request();
        GitHubPullRequestDetails remote = githubClient.findOpenPullRequest(installation.getProviderInstallationId(),
                github.getOwnerLogin(), github.getName(), worktree.getSourceBranch(), request.getTargetBranch());
        if (remote != null) {
            log.info("merge request push skipped projectId={} taskId={} repositoryId={} branch={} reason=remote_open_pr_found remoteNumber={} remoteHead={}",
                    claim.task().getProjectId(), claim.task().getId(), request.getRepositoryId(),
                    worktree.getSourceBranch(), remote.number(), remote.headSha());
            return remote;
        }
        // 创建 PR 前的 Push 已在 Diff 交付阶段完成并持久化为 PUSHED。这里仅调用 GitHub API，
        // 不把 Push 与 MR 创建重新混为一个事实。
        return githubClient.createPullRequest(installation.getProviderInstallationId(), github.getOwnerLogin(),
                github.getName(), new GitHubPullRequestCreateRequest(request.getTitle(), null,
                        worktree.getSourceBranch(), request.getTargetBranch()));
    }

    /**
     * GitHub 创建 PR 只接收 source branch，而不是固定 SHA。若在领取创建操作后分支发生变化，
     * 后续响应校验即使发现不一致也已经来不及阻止 PR 包含未审核代码，因此必须在外调前复核。
     */
    private void verifyRemoteCreationContext(PreflightGateService gates, UUID projectId, CreateClaim claim,
                                             String expectedTargetCommit) {
        String currentTargetCommit = gates.resolveTargetCommit(projectId, claim.request().getRepositoryId(),
                claim.request().getTargetBranch());
        if (!sameCommit(expectedTargetCommit, currentTargetCommit)) {
            throw new ApiException(HttpStatus.CONFLICT, "PREFLIGHT_CONTEXT_STALE",
                    "目标分支已推进，必须重新执行 Dry Run 并获得 CQ+1");
        }
        GitHubBranchDetails source = githubClient.getBranch(claim.installation().getProviderInstallationId(),
                claim.githubRepository().getOwnerLogin(), claim.githubRepository().getName(),
                claim.worktree().getSourceBranch());
        if (source == null || !sameCommit(claim.worktree().getHeadCommit(), source.commitSha())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_SOURCE_HEAD_CHANGED",
                    "Feature 分支已变化，不能基于旧 Diff 创建或更新 MR");
        }
    }

    /**
     * 本地 {@code OPEN} 只是 GitHub PR 的镜像，不能单独作为幂等成功依据。外部关闭/删除 PR 后，
     * 若仍直接返回本地记录，Task 会被错误收敛为已创建 MR。这里先在事务外查询 GitHub；远端记录
     * 不存在或编号已变化时，短事务关闭旧镜像并重新领取创建操作。整个过程不持有数据库锁进行 HTTP 调用。
     */
    private CreateClaim reconcileExistingOpenMr(UUID projectId, UUID userId, MergeRequestCreateRequest request,
                                                String targetCommit, CreateClaim claim) {
        if (claim.existing() == null) {
            return claim;
        }
        GitHubPullRequestDetails remote = githubClient.findOpenPullRequest(
                claim.installation().getProviderInstallationId(), claim.githubRepository().getOwnerLogin(),
                claim.githubRepository().getName(), claim.worktree().getSourceBranch(), request.getTargetBranch());
        if (remote != null && sameProviderNumber(claim.existing(), remote)) {
            // GitHub 的 source branch 是创建 PR 的真实输入；必须同时校验提交，避免把远端后来
            // 推送的未审查代码误视为当前 Task 的幂等 MR。
            validateRemote(claim, remote);
            return refreshExistingOpenMr(projectId, userId, request, targetCommit, claim, remote);
        }
        return retireStaleExistingAndClaim(projectId, userId, request, targetCommit, claim);
    }

    private boolean sameProviderNumber(MergeRequestEntity existing, GitHubPullRequestDetails remote) {
        return existing.getProviderNumber() != null && remote != null
                && existing.getProviderNumber().longValue() == remote.number();
    }

    private CreateClaim refreshExistingOpenMr(UUID projectId, UUID userId, MergeRequestCreateRequest request,
                                              String targetCommit, CreateClaim claim,
                                              GitHubPullRequestDetails remote) {
        MergeRequestEntity refreshed = inTransaction(() -> {
            MergeRequestEntity current = mergeRequestMapper.selectByIdForUpdate(claim.existing().getId());
            if (current == null || !"OPEN".equals(current.getStatus()) || !sameProviderNumber(current, remote)) {
                return null;
            }
            current.setSourceBranch(remote.headBranch());
            current.setTargetBranch(remote.baseBranch());
            current.setHeadCommit(remote.headSha());
            if (remote.title() != null) current.setTitle(remote.title());
            current.setStatus(toLocalStatus(remote));
            current.setMergeable(remote.mergeable());
            current.setMergeableState(remote.mergeableState());
            current.setBaseSha(remote.baseSha());
            current.setSyncedAt(LocalDateTime.now(ZoneOffset.UTC));
            mergeRequestMapper.updateById(current);
            return current;
        });
        return refreshed == null ? retireStaleExistingAndClaim(projectId, userId, request, targetCommit, claim)
                : new CreateClaim(claim.task(), claim.worktree(),
                claim.githubRepository(), claim.installation(), claim.request(), claim.operation(), claim.token(), refreshed);
    }

    private CreateClaim retireStaleExistingAndClaim(UUID projectId, UUID userId, MergeRequestCreateRequest request,
                                                    String targetCommit, CreateClaim claim) {
        return inTransaction(() -> {
            MergeRequestEntity current = mergeRequestMapper.selectByIdForUpdate(claim.existing().getId());
            // 领取后本地镜像可能已被并发同步/清理删除。此时仍继续重新领取创建操作；使用领取时的
            // 快照仅作 best-effort 关闭，不会把不存在的记录当作远端 PR 成功事实。
            if (current == null) current = claim.existing();
            if (current != null && "OPEN".equals(current.getStatus())) {
                current.setStatus("CLOSED");
                current.setSyncedAt(LocalDateTime.now(ZoneOffset.UTC));
                mergeRequestMapper.updateById(current);
            }
            return claimCreate(projectId, userId, request, targetCommit);
        });
    }

    private boolean sameCommit(String expected, String actual) {
        return expected != null && actual != null && expected.equalsIgnoreCase(actual);
    }

    private void pushBranch(TaskEntity task, UUID repositoryId, WorkspaceRepositoryEntity worktree,
                            GitHubRepositoryEntity github, GitHubInstallationEntity installation, String mode) {
        String fullName = github.getOwnerLogin() + "/" + github.getName();
        String grantId = credentialService.generateGrant(installation.getTeamId(), task.getProjectId(),
                installation.getProviderInstallationId(), fullName, worktree.getSourceBranch(),
                worktree.getHeadCommit(), GitCredentialPurpose.PUSH);
        log.info("branch push starting projectId={} taskId={} repositoryId={} branch={} mode={} expectedHeadCommit={}",
                task.getProjectId(), task.getId(), repositoryId, worktree.getSourceBranch(), mode, worktree.getHeadCommit());
        WorkerGitPushResponse pushed;
        try {
            pushed = workerClient.pushWorkspaceBranch(task.getWorkspaceId(), repositoryId,
                    new WorkerGitPushRequest().setExpectedHeadCommit(worktree.getHeadCommit())
                            .setCredentialGrantId(grantId));
        } catch (ApiException failure) {
            log.warn("branch push failed projectId={} taskId={} repositoryId={} branch={} status={} code={} message={}",
                    task.getProjectId(), task.getId(), repositoryId, worktree.getSourceBranch(),
                    failure.status(), failure.code(), failure.getMessage());
            throw new ApiException(failure.status(), "WORKER_PUSH_FAILED",
                    "Failed to push branch via Sandbox Worker: " + failure.getMessage());
        }
        if (pushed == null || !pushed.isVerified() || !worktree.getHeadCommit().equals(pushed.getHeadCommit())) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKER_PUSH_VERIFICATION_FAILED",
                    "Sandbox Worker push verification failed or HEAD mismatch");
        }
        log.info("branch push verified projectId={} taskId={} repositoryId={} branch={} headCommit={}",
                task.getProjectId(), task.getId(), repositoryId, worktree.getSourceBranch(), pushed.getHeadCommit());
    }

    private void validateRemote(CreateClaim claim, GitHubPullRequestDetails remote) {
        if (remote == null || remote.number() <= 0 || remote.headSha() == null
                || !claim.worktree().getHeadCommit().equalsIgnoreCase(remote.headSha())
                || !claim.worktree().getSourceBranch().equals(remote.headBranch())
                || !claim.request().getTargetBranch().equals(remote.baseBranch())) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_PR_RESPONSE_INVALID",
                    "GitHub returned an invalid Pull Request response");
        }
    }

    private void recordRemoteCreated(CreateClaim claim, GitHubPullRequestDetails remote) {
        if (claim.operation() == null) return;
        inTransaction(() -> {
            MergeRequestDeliveryOperationEntity operation = deliveryOperationMapper.selectByIdForUpdate(claim.operation().getId());
            requireOperationClaim(operation, claim.token());
            operation.setStatus("REMOTE_CREATED");
            operation.setProviderNumber((long) remote.number());
            operation.setLeaseExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plus(Duration.ofMinutes(20)));
            operation.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            deliveryOperationMapper.updateById(operation);
            return null;
        });
    }

    private CreateFinalization finalizeCreate(CreateClaim claim, GitHubPullRequestDetails remote) {
        MergeRequestDeliveryOperationEntity operation = claim.operation() == null ? null
                : deliveryOperationMapper.selectByIdForUpdate(claim.operation().getId());
        if (operation != null) requireOperationClaim(operation, claim.token());
        MergeRequestEntity existing = mergeRequestMapper.selectOne(Wrappers.<MergeRequestEntity>lambdaQuery()
                .eq(MergeRequestEntity::getProjectRepositoryId, claim.request().getRepositoryId())
                .eq(MergeRequestEntity::getProvider, "GITHUB")
                .eq(MergeRequestEntity::getProviderNumber, (long) remote.number()).last("LIMIT 1"));
        MergeRequestEntity mr = existing == null ? new MergeRequestEntity() : existing;
        if (existing == null) {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            mr.setId(UuidV7.next());
            mr.setProjectRepositoryId(claim.request().getRepositoryId());
            mr.setTaskId(claim.task().getId());
            mr.setWorkspaceId(claim.task().getWorkspaceId());
            mr.setProvider("GITHUB");
            mr.setProviderNumber((long) remote.number());
            mr.setSourceBranch(remote.headBranch());
            mr.setTargetBranch(remote.baseBranch());
            mr.setHeadCommit(remote.headSha());
            mr.setTitle(remote.title() == null ? claim.request().getTitle() : remote.title());
            mr.setStatus(toLocalStatus(remote));
            mr.setQualityGateStatus("PENDING");
            mr.setSyncedAt(now);
            mr.setAuthorUserId(operation == null ? claim.task().getCreatedBy() : operation.getActorUserId());
            mr.setCreatedAt(now);
            mergeRequestMapper.insert(mr);
            if (claim.task().getRequirementGroupId() != null) {
                MergeRequestGroupEntity relation = new MergeRequestGroupEntity();
                relation.setMergeRequestId(mr.getId());
                relation.setRequirementGroupId(claim.task().getRequirementGroupId());
                mergeRequestGroupMapper.insert(relation);
            }
            refreshQualityGate(mr);
        }
        if (operation != null) {
            operation.setStatus("COMPLETED");
            operation.setMergeRequestId(mr.getId());
            operation.setProviderNumber((long) remote.number());
            operation.setClaimToken(null);
            operation.setLeaseExpiresAt(null);
            operation.setFailureCode(null);
            operation.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            deliveryOperationMapper.updateById(operation);
        }
        return new CreateFinalization(mr, markMrCreatedAndCompleteTask(claim));
    }

    /**
     * PR 已由 GitHub 确认存在后，才把本地 Diff 标记为 MR_CREATED。多仓库不作为分布式事务：
     * 每次只推进当前仓库，最后一个仓库完成时才收敛 MR_FIRST Task。
     */
    private TaskEntity markMrCreatedAndCompleteTask(CreateClaim claim) {
        if (diffMapper == null) return null;
        DiffEntity diff = diffMapper.selectAcceptedCommittedForMr(claim.task().getId(), claim.task().getProjectId(),
                claim.task().getWorkspaceId(), claim.request().getRepositoryId(), claim.worktree().getHeadCommit());
        if (diff == null || diff.getId() == null) return null;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!"MR_CREATED".equals(diff.getDeliveryStatus())) {
            diffMapper.markDelivered(diff.getId(), now);
            eventService.publish(claim.task().getProjectId(), claim.task().getRequirementGroupId(),
                    "delivery.repository.updated", diff.getId().toString(), Map.of(
                            "projectId", claim.task().getProjectId(), "taskId", claim.task().getId(),
                            "diffId", diff.getId(), "repositoryId", claim.request().getRepositoryId(),
                            "deliveryStatus", "MR_CREATED"));
        }
        if (!"MR_FIRST".equals(claim.task().getDeliveryMode())) return null;

        List<DiffEntity> deliveries = diffMapper.selectList(Wrappers.<DiffEntity>lambdaQuery()
                .eq(DiffEntity::getTaskId, claim.task().getId())
                .eq(DiffEntity::getProjectId, claim.task().getProjectId())
                .eq(DiffEntity::getWorkspaceId, claim.task().getWorkspaceId())
                .eq(DiffEntity::getStatus, "ACCEPTED"));
        if (deliveries == null || deliveries.isEmpty()
                || deliveries.stream().anyMatch(value -> !"MR_CREATED".equals(value.getDeliveryStatus()))) {
            return null;
        }
        TaskEntity task = taskMapper.selectByIdForUpdate(claim.task().getId());
        if (task == null || !claim.task().getProjectId().equals(task.getProjectId())
                || !"MR_FIRST".equals(task.getDeliveryMode())
                || !"WAITING_PREFLIGHT".equals(task.getStatus())) {
            return null;
        }
        task.setStatus("SUCCEEDED");
        task.setUpdatedAt(now);
        taskMapper.updateById(task);
        return task;
    }

    private void publishTaskCompleted(TaskEntity task) {
        if (task == null) return;
        eventService.publish(task.getProjectId(), task.getRequirementGroupId(), "task.updated", task.getId().toString(),
                TaskEventPayloads.taskUpdated(task));
        notificationService.notify(task.getCreatedBy(), task.getProjectId(), task.getRequirementGroupId(),
                "TASK_COMPLETED", "任务完成：" + task.getTitle(), task.getRequirement(), task.getId().toString());
    }

    /**
     * 真实 PR 已在 GitHub 创建并落库后回一条任务状态卡。多仓库任务一仓一条；卡片的客户端幂等键
     * 使用真实 MR ID，重试创建或已存在 MR 的本地修复不会重复刷群。发送消息属于通知，不参与
     * MR 创建事务，失败只记录日志，不能把远端已存在的 MR 标成失败。
     */
    private void publishMergeRequestCard(TaskEntity task, MergeRequestEntity mr) {
        if (messageService == null || task == null || mr == null || mr.getId() == null
                || task.getRequirementGroupId() == null) {
            return;
        }
        Map<String, Object> mergeRequest = new LinkedHashMap<>();
        mergeRequest.put("id", mr.getId().toString());
        if (mr.getProviderNumber() != null) mergeRequest.put("number", mr.getProviderNumber());
        if (mr.getTitle() != null) mergeRequest.put("title", mr.getTitle());
        String webUrl = mrWebUrl(mr);
        if (webUrl != null) mergeRequest.put("webUrl", webUrl);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("taskId", task.getId().toString());
        content.put("status", "MR_CREATED");
        content.put("phase", "DELIVERY");
        content.put("message", "Merge Request 已创建");
        if (mr.getProjectRepositoryId() != null) content.put("repositoryId", mr.getProjectRepositoryId().toString());
        content.put("mergeRequest", mergeRequest);
        MessageSendRequest body = new MessageSendRequest();
        body.setType("TASK_STATUS");
        body.setClientMessageId("task-card-" + task.getId());
        body.setContent(content);
        try {
            UUID senderId = orchestratorAgents == null ? null : orchestratorAgents.resolveIdForTask(task);
            if (senderId != null) {
                messageService.upsertTaskStatusCard(task.getRequirementGroupId(), senderId, body);
            } else {
                messageService.upsertTaskStatusCard(task.getRequirementGroupId(), null, body);
            }
        } catch (RuntimeException failure) {
            log.warn("merge request card skipped taskId={} mergeRequestId={}: {}",
                    task.getId(), mr.getId(), failure.getMessage());
        }
    }

    private void markCreateFailed(CreateClaim claim, RuntimeException failure) {
        if (claim.operation() == null) return;
        try {
            inTransaction(() -> {
                MergeRequestDeliveryOperationEntity operation = deliveryOperationMapper.selectByIdForUpdate(claim.operation().getId());
                if (operation == null || !claim.token().equals(operation.getClaimToken())
                        || "COMPLETED".equals(operation.getStatus())) return null;
                operation.setStatus("FAILED");
                operation.setClaimToken(null);
                operation.setLeaseExpiresAt(null);
                operation.setFailureCode(failure instanceof ApiException api ? api.code() : "MR_CREATION_FAILED");
                operation.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                deliveryOperationMapper.updateById(operation);
                return null;
            });
        } catch (RuntimeException ignored) {
        }
    }

    private void requireOperationClaim(MergeRequestDeliveryOperationEntity operation, String token) {
        if (operation == null || token == null || !token.equals(operation.getClaimToken())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_CREATION_CLAIM_LOST", "Pull Request creation claim was lost");
        }
    }

    private String operationKey(UUID projectId, TaskEntity task, WorkspaceRepositoryEntity worktree,
                                MergeRequestCreateRequest request) {
        try {
            String value = projectId + "\n" + task.getId() + "\n" + task.getWorkspaceId() + "\n"
                    + request.getRepositoryId() + "\n" + worktree.getSourceBranch() + "\n"
                    + request.getTargetBranch() + "\n" + worktree.getHeadCommit();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    /**
     * 创建 MR 的交付前置校验：head 必须来自本任务「已获准进入交付且已推送」的 Diff。
     * DIFF_FIRST 为用户确认（confirmationSource=USER）；MR_FIRST 为系统自动授权（SYSTEM）——
     * 两种模式统一走同一校验（Diff ACCEPTED + 批次 ACCEPTED + headCommit 一致），
     * 未定型（null）交付模式仍拒绝。客户端无法通过本接口伪造 head 归属。
     */
    private void requireAcceptedDelivery(TaskEntity task, WorkspaceRepositoryEntity worktree, UUID repositoryId) {
        if (task.getDeliveryMode() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_DELIVERY_MODE_INVALID",
                    "Task delivery mode is not determined yet");
        }
        if (diffMapper == null || diffMapper.selectAcceptedCommittedForMr(task.getId(), task.getProjectId(),
                task.getWorkspaceId(), repositoryId, worktree.getHeadCommit()) == null) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_REVIEWED_DIFF_REQUIRED",
                    "The current Workspace HEAD is not an accepted and pushed Task Diff");
        }
    }

    /**
     * 推送只要求本任务 Diff 已由用户或系统接受且已真实创建 Commit。MR 创建才额外要求
     * 远端已核验的 PUSHED/MR_CREATED 事实，两个阶段不能混用同一门禁。
     */
    private void requireAcceptedCommitForPush(TaskEntity task, WorkspaceRepositoryEntity worktree, UUID repositoryId) {
        if (task.getDeliveryMode() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_DELIVERY_MODE_INVALID",
                    "Task delivery mode is not determined yet");
        }
        if (diffMapper == null || diffMapper.selectAcceptedCommittedForPush(task.getId(), task.getProjectId(),
                task.getWorkspaceId(), repositoryId, worktree.getHeadCommit()) == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PUSH_ACCEPTED_COMMIT_REQUIRED",
                    "The current Workspace HEAD is not an accepted and committed Task Diff");
        }
    }

    /**
     * MR_FIRST 只能在全部仓库已 commit/push 后的预检阶段创建 MR，不能让部分交付失败的
     * Task 绕过 retry-delivery 直接生成不完整的 MR 集合。任务已完成后，既有开放 MR
     * 可以安全幂等重放；若远端已关闭，则仍需通过当前预检后重建同一已审核 HEAD 的 MR。
     * Diff-first 的手动建 MR 则要求此前的确认提交/推送已经收敛为 SUCCEEDED。
     */
    private void requireTaskReadyForMr(TaskEntity task, WorkspaceRepositoryEntity worktree) {
        if ("MR_FIRST".equals(task.getDeliveryMode())) {
            boolean completedTask = "SUCCEEDED".equals(task.getStatus()) && worktree.getHeadCommit() != null;
            if (!"WAITING_PREFLIGHT".equals(task.getStatus()) && !completedTask) {
                throw new ApiException(HttpStatus.CONFLICT, "MR_TASK_NOT_WAITING_PREFLIGHT",
                        "MR_FIRST Task must finish commit/push delivery before creating a Pull Request");
            }
            return;
        }
        if ("DIFF_FIRST".equals(task.getDeliveryMode()) && !"SUCCEEDED".equals(task.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_TASK_NOT_DELIVERED",
                    "Diff-first Task must complete accepted Diff delivery before creating a Pull Request");
        }
    }

    private void requireOperationContext(MergeRequestDeliveryOperationEntity operation, UUID projectId,
                                         TaskEntity task, WorkspaceRepositoryEntity worktree, MergeRequestCreateRequest request) {
        if (!projectId.equals(operation.getProjectId()) || !task.getId().equals(operation.getTaskId())
                || !task.getWorkspaceId().equals(operation.getWorkspaceId())
                || !request.getRepositoryId().equals(operation.getProjectRepositoryId())
                || !worktree.getHeadCommit().equals(operation.getHeadCommit())
                || !worktree.getSourceBranch().equals(operation.getSourceBranch())
                || !request.getTargetBranch().equals(operation.getTargetBranch())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_OPERATION_CONTEXT_CHANGED",
                    "Pull Request delivery operation no longer belongs to this Task Workspace state");
        }
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactions == null ? action.get() : transactions.execute(status -> action.get());
    }

    private MergeRequestSummaryResponse summary(MergeRequestEntity mr) {
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr),
                mrWebUrl(mr));
    }

    private record CreateClaim(TaskEntity task, WorkspaceRepositoryEntity worktree,
                               GitHubRepositoryEntity githubRepository, GitHubInstallationEntity installation,
                               MergeRequestCreateRequest request, MergeRequestDeliveryOperationEntity operation,
                               String token, MergeRequestEntity existing) {
    }

    private PreflightGateService requirePreflightGates() {
        if (preflightGates == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MR_PREFLIGHT_GATE_UNAVAILABLE",
                    "MR 前预检组件不可用，不能创建 MR");
        }
        return preflightGates;
    }

    private record CreateFinalization(MergeRequestEntity mergeRequest, TaskEntity completedTask) {
    }

    private record MergeClaim(MergeRequestEntity mergeRequest, GitHubRepositoryEntity githubRepository,
                              GitHubInstallationEntity installation, String operationId, boolean alreadyCompleted) {
    }

    /**
     * 查询门禁检查汇总（契约 §21：包装为 {status, requiredChecks, items[]}）。
     */
    public MergeRequestChecksResponse checks(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        List<MergeRequestCheckResponse> items = qualityCheckMapper.selectList(Wrappers.<QualityCheckResultEntity>lambdaQuery()
                .eq(QualityCheckResultEntity::getMergeRequestId, mergeRequestId)
                .orderByAsc(QualityCheckResultEntity::getCreatedAt)).stream().map(this::toCheck).toList();
        QualityGateResponse gate = qualityGate(mr);
        return new MergeRequestChecksResponse(gate.getStatus(), gate.getRequiredChecks(), items);
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
     * Refreshes the local mirror from GitHub's current Pull Request state.
     */
    public MergeRequestSummaryResponse sync(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        MergeRequestEntity mr = requireMr(projectId, mergeRequestId);
        GitHubRepositoryEntity githubRepository = requireGitHubRepository(projectId, mr.getProjectRepositoryId());
        GitHubInstallationEntity installation = requireInstallation(githubRepository);
        GitHubPullRequestDetails remote = githubClient.getPullRequest(installation.getProviderInstallationId(),
                githubRepository.getOwnerLogin(), githubRepository.getName(), requireProviderNumber(mr));
        mr = inTransaction(() -> persistRemoteState(projectId, mergeRequestId, remote));
        publishUpdated(mr);
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr),
                mrWebUrl(mr));
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
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr),
                mrWebUrl(mr));
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
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr),
                mrWebUrl(mr));
    }

    /**
     * 请求真实 GitHub 合并。数据库只在认领和落库阶段持有短事务，网络调用始终在事务外执行。
     */
    public MergeRequestSummaryResponse merge(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectAdmin(projectId, userId);
        MergeClaim claim = inTransaction(() -> claimMerge(projectId, mergeRequestId));
        if (claim.alreadyCompleted()) {
            return summary(claim.mergeRequest());
        }
        try {
            GitHubPullRequestDetails remote = githubClient.getPullRequest(
                    claim.installation().getProviderInstallationId(), claim.githubRepository().getOwnerLogin(),
                    claim.githubRepository().getName(), requireProviderNumber(claim.mergeRequest()));
            if (remote == null || !remote.merged()) {
                // 用合并时重新拉取的新鲜 mergeable 状态拦截冲突，不依赖可能过期的落库值；
                // remote 或 mergeable 为 null 表示 GitHub 尚未算完，交由 GitHub 原生合并判定。
                if (remote != null && remote.mergeable() != null && !remote.mergeable()) {
                    throw new ApiException(HttpStatus.CONFLICT, "MR_HAS_CONFLICTS",
                            "MR 存在冲突或不可合并（mergeable_state=" + remote.mergeableState() + "），请解决后重试");
                }
                GitHubPullRequestMergeResult result = githubClient.mergePullRequest(
                        claim.installation().getProviderInstallationId(), claim.githubRepository().getOwnerLogin(),
                        claim.githubRepository().getName(), requireProviderNumber(claim.mergeRequest()),
                        new GitHubPullRequestMergeRequest(
                                "Merge " + (claim.mergeRequest().getTitle() == null
                                        ? "Pull Request" : claim.mergeRequest().getTitle()),
                                null, "squash", claim.mergeRequest().getHeadCommit()));
                if (!result.merged()) {
                    throw new ApiException(HttpStatus.CONFLICT, "GITHUB_MERGE_NOT_COMPLETED",
                            result.message() == null ? "GitHub did not merge the Pull Request" : result.message());
                }
            }
            MergeRequestEntity merged = inTransaction(() -> completeMerge(projectId, mergeRequestId,
                    claim.operationId()));
            publishUpdated(merged);
            return summary(merged);
        } catch (RuntimeException failure) {
            inTransaction(() -> {
                failMerge(mergeRequestId, claim.operationId());
                return null;
            });
            throw failure;
        }
    }

    private MergeClaim claimMerge(UUID projectId, UUID mergeRequestId) {
        MergeRequestEntity mr = mergeRequestMapper.selectByIdForUpdate(mergeRequestId);
        if (mr == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        ProjectRepositoryEntity repository = projectRepositoryMapper.selectById(mr.getProjectRepositoryId());
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        GitHubRepositoryEntity githubRepository = requireGitHubRepository(projectId, mr.getProjectRepositoryId());
        GitHubInstallationEntity installation = requireInstallation(githubRepository);
        if ("MERGED".equals(mr.getStatus()) && "COMPLETED".equals(mr.getMergeOperationStatus())) {
            return new MergeClaim(mr, githubRepository, installation, mr.getMergeOperationId(), true);
        }
        if (!"OPEN".equals(mr.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_REQUEST_NOT_OPEN",
                    "Only an open Pull Request can be merged");
        }
        if (!"PASSED".equals(qualityGate(mr).getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "QUALITY_GATE_NOT_PASSED", "质量门禁未通过，无法合并");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if ("RUNNING".equals(mr.getMergeOperationStatus()) && mr.getMergeLeaseExpiresAt() != null
                && mr.getMergeLeaseExpiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_REQUEST_MERGE_IN_PROGRESS", "该 MR 正在合并中");
        }
        String operationId = mr.getMergeOperationId();
        if (operationId == null || operationId.isBlank()) {
            operationId = UuidV7.next().toString();
        }
        mr.setMergeOperationId(operationId);
        mr.setMergeOperationStatus("RUNNING");
        mr.setMergeLeaseExpiresAt(now.plus(MERGE_OPERATION_LEASE));
        mergeRequestMapper.updateById(mr);
        return new MergeClaim(mr, githubRepository, installation, operationId, false);
    }

    private MergeRequestEntity completeMerge(UUID projectId, UUID mergeRequestId, String operationId) {
        MergeRequestEntity current = mergeRequestMapper.selectByIdForUpdate(mergeRequestId);
        if (current == null || !operationId.equals(current.getMergeOperationId())) {
            throw new ApiException(HttpStatus.CONFLICT, "MERGE_OPERATION_LOST", "合并操作租约已失效");
        }
        ProjectRepositoryEntity repository = projectRepositoryMapper.selectById(current.getProjectRepositoryId());
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        current.setStatus("MERGED");
        current.setMergeOperationStatus("COMPLETED");
        current.setMergeLeaseExpiresAt(null);
        current.setProviderUpdatedAt(now);
        current.setSyncedAt(now);
        mergeRequestMapper.updateById(current);
        return current;
    }

    private void failMerge(UUID mergeRequestId, String operationId) {
        MergeRequestEntity current = mergeRequestMapper.selectByIdForUpdate(mergeRequestId);
        if (current == null || !operationId.equals(current.getMergeOperationId())
                || "COMPLETED".equals(current.getMergeOperationStatus())) {
            return;
        }
        current.setMergeOperationStatus("FAILED");
        current.setMergeLeaseExpiresAt(null);
        mergeRequestMapper.updateById(current);
    }

    // ---------- 私有辅助 ----------

    private MergeRequestEntity persistRemoteState(UUID projectId, UUID mergeRequestId,
                                                  GitHubPullRequestDetails remote) {
        MergeRequestEntity current = mergeRequestMapper.selectByIdForUpdate(mergeRequestId);
        if (current == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        ProjectRepositoryEntity repository = projectRepositoryMapper.selectById(current.getProjectRepositoryId());
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "MERGE_REQUEST_NOT_FOUND", "MR 不存在或不可见");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        current.setProviderNumber((long) remote.number());
        current.setSourceBranch(remote.headBranch());
        current.setTargetBranch(remote.baseBranch());
        current.setHeadCommit(remote.headSha());
        if (remote.title() != null) current.setTitle(remote.title());
        // 较早发起的同步请求不得把已经落库的 MERGED 终态覆盖回 OPEN。
        if (!"MERGED".equals(current.getStatus()) || remote.merged()) {
            current.setStatus(toLocalStatus(remote));
        }
        current.setProviderUpdatedAt(now);
        current.setSyncedAt(now);
        current.setMergeable(remote.mergeable());
        current.setMergeableState(remote.mergeableState());
        current.setBaseSha(remote.baseSha());
        mergeRequestMapper.updateById(current);
        refreshQualityGate(current);
        return current;
    }

    /**
     * 创建 MR 后短轮询 GitHub mergeable 状态并落库。
     * GitHub 异步计算合并可行性，未完成时返回 null；命中非 null 即持久化并发 SSE。
     * 返回落库后（mergeable 已刷新）的实体；超时未算完或异常时由调用方决定回退。
     * 网络调用在事务外执行，只在两次请求之间短暂等待。
     */
    private MergeRequestEntity pollMergeability(UUID projectId, GitHubRepositoryEntity repo,
                                               GitHubInstallationEntity installation, MergeRequestEntity mr) {
        if (repo == null || installation == null || mr.getProviderNumber() == null) {
            return mr;
        }
        for (int attempt = 0; attempt < MERGEABLE_POLL_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(MERGEABLE_POLL_INTERVAL.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return mr;
                }
            }
            GitHubPullRequestDetails remote = githubClient.getPullRequest(installation.getProviderInstallationId(),
                    repo.getOwnerLogin(), repo.getName(), requireProviderNumber(mr));
            if (remote == null || remote.mergeable() == null) {
                continue;
            }
            MergeRequestEntity updated = inTransaction(() -> persistRemoteState(projectId, mr.getId(), remote));
            publishUpdated(updated);
            return updated;
        }
        return mr;
    }

    /**
     * 加载 MR 并校验其仓库属于路径项目，禁止仅凭 UUID 跨项目查询。
     */
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

    /**
     * CQ reviewer must differ from the MR author.
     */
    private void requireCqReviewer(MergeRequestEntity mr, UUID userId) {
        if (userId.equals(mr.getAuthorUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CQ_REVIEWER_NOT_ALLOWED", "MR 作者不能审查自己的 CQ");
        }
    }

    /**
     * 写入质量门禁检查结果（attemptNo 在同提交同类型内递增）。
     */
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

    /**
     * 汇总目标分支质量门禁：必检项全部 PASSED → PASSED；任一 FAILED → FAILED；缺失/运行中 → PENDING。
     */
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

    /**
     * 批量汇总多个 MR 的目标分支质量门禁状态，消除 N+1 查询
     */
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
            // Testset 由创建 MR 前的 Dry Run 真实执行；MR 后门禁不伪造一份没有写入者的 TESTSET 检查。
            List<String> checks = required.stream().filter(check -> !"TESTSET".equals(check)).distinct().toList();

            List<QualityCheckResultEntity> mrChecks = allChecks.stream()
                    .filter(c -> java.util.Objects.equals(c.getMergeRequestId(), mr.getId()) && java.util.Objects.equals(c.getCommitSha(), mr.getHeadCommit()))
                    .toList();

            String status = computeGateStatusFromList(mrChecks, checks, List.of());
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

    /**
     * 取 (mrId, checkType, commitSha[, testsetId]) 的最新 attempt_no 检查结果。
     */
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

    /**
     * 重算并持久化 MR 的门禁汇总状态。
     */
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
        // 已撤权（REVOKED）的仓库镜像不可再用于创建/同步/合并 PR，调用 GitHub 前统一拒绝
        if (!"AUTHORIZED".equals(repository.getAuthorizationStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_REPOSITORY_UNAVAILABLE",
                    "The bound GitHub repository authorization has been revoked");
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

    /**
     * 批量取 MR 的需求群ID映射，避免列表 N+1 查询。
     */
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
        p.put("headCommit", mr.getHeadCommit());
        p.put("providerUpdatedAt", iso(mr.getProviderUpdatedAt()));
        p.put("qualityGateStatus", mr.getQualityGateStatus());
        p.put("mergeable", mr.getMergeable());
        p.put("mergeableState", mr.getMergeableState());
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

    /**
     * 构造 MR 的 GitHub Web 地址（repo 或 providerNumber 缺失时返回 null，不抛错）。
     */
    private String mrWebUrl(MergeRequestEntity mr) {
        ProjectRepositoryEntity binding = projectRepositoryMapper.selectById(mr.getProjectRepositoryId());
        if (binding == null || mr.getProviderNumber() == null) {
            return null;
        }
        GitHubRepositoryEntity repo = githubRepositoryMapper.selectById(binding.getRepositoryId());
        if (repo == null || repo.getOwnerLogin() == null || repo.getName() == null) {
            return null;
        }
        return "https://github.com/" + repo.getOwnerLogin() + "/" + repo.getName() + "/pull/"
                + mr.getProviderNumber();
    }

    /**
     * 批量构造 MR 的 GitHub Web 地址（列表页用，避免逐条查询）。
     */
    private Map<UUID, String> webUrlsByMr(List<MergeRequestEntity> rows) {
        Set<UUID> bindingIds = rows.stream().map(MergeRequestEntity::getProjectRepositoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (bindingIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ProjectRepositoryEntity> bindingById = projectRepositoryMapper
                .selectBatchIds(bindingIds).stream()
                .collect(Collectors.toMap(ProjectRepositoryEntity::getId, Function.identity()));
        Set<UUID> githubIds = bindingById.values().stream().map(ProjectRepositoryEntity::getRepositoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, GitHubRepositoryEntity> githubById = githubIds.isEmpty() ? Collections.emptyMap()
                : githubRepositoryMapper.selectBatchIds(githubIds).stream()
                .collect(Collectors.toMap(GitHubRepositoryEntity::getId, Function.identity()));
        Map<UUID, String> result = new HashMap<>();
        for (MergeRequestEntity mr : rows) {
            ProjectRepositoryEntity binding = bindingById.get(mr.getProjectRepositoryId());
            GitHubRepositoryEntity repo = binding == null ? null : githubById.get(binding.getRepositoryId());
            if (repo == null || mr.getProviderNumber() == null) {
                result.put(mr.getId(), null);
            } else {
                result.put(mr.getId(), "https://github.com/" + repo.getOwnerLogin() + "/" + repo.getName()
                        + "/pull/" + mr.getProviderNumber());
            }
        }
        return result;
    }

    /**
     * 查询 MR 关联的已接受 Diff ID（同任务、同仓库、status=ACCEPTED，取最新）；无则 null。
     * 供 MR 详情「变更 / 评论」Tab 直接定位 Diff。
     */
    private String acceptedDiffId(MergeRequestEntity mr) {
        if (mr.getTaskId() == null) {
            return null;
        }
        DiffEntity diff = diffMapper.selectOne(Wrappers.<DiffEntity>lambdaQuery()
                .eq(DiffEntity::getTaskId, mr.getTaskId())
                .eq(DiffEntity::getProjectRepositoryId, mr.getProjectRepositoryId())
                .eq(DiffEntity::getStatus, "ACCEPTED")
                .orderByDesc(DiffEntity::getCreatedAt).last("LIMIT 1"));
        return diff == null ? null : id(diff.getId());
    }

    private MergeRequestSummaryResponse toSummary(MergeRequestEntity mr, List<String> groupIds,
                                                  QualityGateResponse gate, String webUrl) {
        return new MergeRequestSummaryResponse(id(mr.getId()), id(mr.getProjectRepositoryId()), groupIds,
                mr.getProvider(), mr.getProviderNumber(), mr.getSourceBranch(), mr.getTargetBranch(), mr.getStatus(),
                mr.getHeadCommit(), mr.getMergeable(), mr.getMergeableState(), gate, mr.getTitle(), webUrl,
                iso(mr.getCreatedAt()));
    }

    private MergeRequestDetailResponse toDetail(MergeRequestEntity mr, List<String> groupIds,
                                                QualityGateResponse gate, String webUrl, String diffId) {
        return new MergeRequestDetailResponse(id(mr.getId()), id(mr.getProjectRepositoryId()), groupIds,
                mr.getProvider(), mr.getProviderNumber(), mr.getSourceBranch(), mr.getTargetBranch(), mr.getStatus(),
                mr.getHeadCommit(), mr.getMergeable(), mr.getMergeableState(), mr.getTitle(), null, webUrl, diffId,
                gate, id(mr.getAuthorUserId()), iso(mr.getSyncedAt()), iso(mr.getCreatedAt()));
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
