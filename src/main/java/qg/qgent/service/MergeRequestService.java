package qg.qgent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

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
    private static final long LIST_CACHE_TTL_MILLIS = 2_000L;
    private static final int LIST_CACHE_MAX_ENTRIES = 128;
    private final ConcurrentHashMap<String, CachedMrPage> listCache = new ConcurrentHashMap<>();
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
    /** PUSH 的一次 HTTP 应答丢失不代表远端未接收；最多重放一次同一 branch/head 的幂等推送。 */
    private static final int WORKER_PUSH_MAX_ATTEMPTS = 2;
    private static final long WORKER_PUSH_INITIAL_BACKOFF_MILLIS = 250L;

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
    /** 人工 CQ 审查记录的显示名快照；缺失时仍保留 reviewerUserId。 */
    private UserMapper userMapper;
    /** GitHub 合并属于慢速外部 IO，生产环境复用编排线程池异步执行。 */
    private Executor mergeExecutor;
    /**
     * PR 创建成功后的检查写入/通知钩子。@Autowired setter 注入：避免主构造器继续膨胀，
     * 也保持既有纯 Mockito 测试构造器兼容（未注入时钩子静默跳过）。
     */
    private MrQualityGateService qualityGates;
    /** P1 MR 前预检门禁。缺失时必须拒绝创建 MR，不能降级为绕过门禁。 */
    private PreflightGateService preflightGates;
    /** source branch 进入 MR 生命周期后禁止继续写入。 */
    private WorkBranchDevelopmentGuard developmentGuard;
    /** 已确认创建真实 MR 后的群聊回卡依赖；发送失败不得改变远端 MR 事实。 */
    private MessageService messageService;
    private OrchestratorAgentService orchestratorAgents;
    /** 需求群可见性复用任务中心规则，避免 MR 列表展示用户无法申请的分支。 */
    private GroupService groupService;
    /** TASK_STATUS 卡片仓库映射；通知增强失败不得改变真实 MR 状态。 */
    private TaskStatusRepositoryContextService repositoryContextService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setQualityGates(MrQualityGateService qualityGates) {
        this.qualityGates = qualityGates;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setPreflightGates(PreflightGateService preflightGates) {
        this.preflightGates = preflightGates;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDevelopmentGuard(WorkBranchDevelopmentGuard developmentGuard) {
        this.developmentGuard = developmentGuard;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMessageService(MessageService messageService) {
        this.messageService = messageService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setOrchestratorAgents(OrchestratorAgentService orchestratorAgents) {
        this.orchestratorAgents = orchestratorAgents;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRepositoryContextService(TaskStatusRepositoryContextService repositoryContextService) {
        this.repositoryContextService = repositoryContextService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setGroupService(GroupService groupService) {
        this.groupService = groupService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUserMapper(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setMergeExecutor(@org.springframework.beans.factory.annotation.Qualifier("taskOrchestratorExecutor") Executor mergeExecutor) {
        this.mergeExecutor = mergeExecutor;
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
        int boundedLimit = clampLimit(limit);
        String key = listCacheKey(projectId, userId, repositoryId, groupId, status, cursor, boundedLimit);
        CachedMrPage cached = listCache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt() > now) {
            return toResponse(await(cached.future()), requestId);
        }
        CachedMrPage candidate = new CachedMrPage(new CompletableFuture<>(), now + LIST_CACHE_TTL_MILLIS);
        if (cached == null) {
            CachedMrPage previous = listCache.putIfAbsent(key, candidate);
            cached = previous == null ? candidate : previous;
        } else if (listCache.replace(key, cached, candidate)) {
            cached = candidate;
        } else {
            cached = listCache.get(key);
        }
        if (cached != candidate) {
            return toResponse(await(cached.future()), requestId);
        }
        try {
            ApiPageResponse<MergeRequestSummaryResponse> result = listUncached(projectId, userId, repositoryId,
                    groupId, status, cursor, boundedLimit, requestId);
            candidate.future().complete(new MrPageData(result.data(), result.page()));
            trimListCache();
            return result;
        } catch (RuntimeException e) {
            candidate.future().completeExceptionally(e);
            listCache.remove(key, candidate);
            throw e;
        }
    }

    private ApiPageResponse<MergeRequestSummaryResponse> toResponse(MrPageData page, String requestId) {
        return new ApiPageResponse<>(page.data(), page.page(), requestId);
    }

    private ApiPageResponse<MergeRequestSummaryResponse> listUncached(UUID projectId, UUID userId, UUID repositoryId,
                                                                       UUID groupId, String status, String cursor,
                                                                       int limit, String requestId) {
        projectAccess.requireProjectMember(projectId, userId);
        boolean projectAdmin = projectAccess.isProjectAdmin(projectId, userId);
        Set<UUID> visibleGroupIds = projectAdmin || groupService == null ? null : visibleGroupIds(projectId, userId);
        if (!projectAdmin && groupService != null && visibleGroupIds.isEmpty()) {
            return emptyPage(requestId);
        }
        if (!projectAdmin && groupService != null && groupId != null && !visibleGroupIds.contains(groupId)) {
            return emptyPage(requestId);
        }
        int size = clampLimit(limit);
        List<UUID> repoIds = projectRepositoryMapper.selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                        .eq(ProjectRepositoryEntity::getProjectId, projectId)).stream()
                .map(ProjectRepositoryEntity::getId).toList();
        if (repoIds.isEmpty()) {
            return emptyPage(requestId);
        }
        boolean includePendingCreate = status == null || status.isBlank()
                || "PENDING_CREATE".equalsIgnoreCase(status);
        List<MergeRequestSummaryResponse> pendingCandidates = includePendingCreate
                ? placeholderMergeRequests(projectId, repositoryId, groupId,
                "WAITING_PREFLIGHT", visibleGroupIds)
                : List.of();
        Set<String> pendingIds = pendingCandidates.stream()
                .map(MergeRequestSummaryResponse::getId).filter(Objects::nonNull).collect(Collectors.toSet());
        UUID cursorUuid = parseCursor(cursor);
        boolean cursorIsPending = cursor != null && pendingIds.contains(cursor);
        QueryWrapper<MergeRequestEntity> query = Wrappers.<MergeRequestEntity>query()
                .in("project_repository_id", repoIds)
                // PENDING_CREATE is a transient projection built below. Older versions
                // persisted those rows, so never let stale placeholders leak back as real MRs.
                .ne("status", "PENDING_CREATE")
                .eq(status != null && !status.isBlank(), "status", status)
                .eq(repositoryId != null, "project_repository_id", repositoryId)
                // While paging through synthetic pending rows, do not apply the synthetic UUID
                // to real MR rows or real rows could be skipped between placeholder pages.
                .lt(!cursorIsPending && cursorUuid != null, "id", cursorUuid)
                .orderByDesc("id")
                .last("LIMIT " + (size + 1));
        if (groupId != null) {
            List<UUID> mrIds = mergeRequestGroupMapper.selectByRequirementGroupId(groupId).stream()
                    .map(MergeRequestGroupEntity::getMergeRequestId).toList();
            if (mrIds.isEmpty()) {
                // Keep the real-MR side empty without discarding Task-backed placeholders.
                query.eq("id", UUID.randomUUID());
            } else {
                query.in("id", mrIds);
            }
        }
        if (!projectAdmin && groupService != null) {
            List<UUID> visibleTaskIds = visibleTaskIds(projectId, visibleGroupIds);
            query.and(wrapper -> {
                wrapper.isNull("task_id");
                if (!visibleTaskIds.isEmpty()) {
                    wrapper.or().in("task_id", visibleTaskIds);
                }
            });
        }
        List<MergeRequestEntity> rows = mergeRequestMapper.selectList(query);
        if (rows == null) rows = List.of();
        Map<UUID, List<String>> groupIdsByMr = groupIdsByMr(rows);
        Map<UUID, QualityGateResponse> gatesByMr = qualityGates(rows);
        Map<UUID, String> webUrlsByMr = webUrlsByMr(rows);
        List<MergeRequestSummaryResponse> items = rows.stream()
                .map(mr -> toSummary(mr, groupIdsByMr.getOrDefault(mr.getId(), List.of()),
                        gatesByMr.get(mr.getId()), webUrlsByMr.get(mr.getId())))
                .collect(Collectors.toCollection(ArrayList::new));

        // Pending placeholders occupy the first cursor phase; after a real MR cursor is
        // returned, the normal real-MR cursor stream resumes and placeholders stay hidden.
        if (!pendingCandidates.isEmpty()) {
            List<MergeRequestSummaryResponse> pending = pendingCandidates.stream()
                    .filter(value -> cursor == null || cursor.isBlank() || cursorIsPending)
                    .filter(value -> !cursorIsPending || comparePlaceholderId(value.getId(), cursor) < 0)
                    .toList();
            List<MergeRequestSummaryResponse> merged = new ArrayList<>(pending.size() + items.size());
            merged.addAll(pending);
            merged.addAll(items);
            items = merged;
        }

        boolean hasMore = items.size() > size || (rows.size() > size && !cursorIsPending);
        if (items.size() > size) {
            items = new ArrayList<>(items.subList(0, size));
        }
        String nextCursor = hasMore && !items.isEmpty() ? items.get(items.size() - 1).getId() : null;
        PageMeta meta = new PageMeta(nextCursor, hasMore);
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
            MergeRequestEntity refreshed;
            try {
                // mergeability 轮询是 best-effort：MR 已创建成功，轮询失败不得影响交付结果。
                refreshed = pollMergeability(projectId, resolvedClaim.githubRepository(), resolvedClaim.installation(), mr);
            } catch (RuntimeException failure) {
                log.warn("mergeability poll failed for MR {}, falling back to created state", mr.getId(), failure);
                refreshed = mr;
            }
            final MergeRequestEntity postCreateMr = refreshed;
            // PR 创建成功后写入 AI_REVIEW 检查并发 MR_PENDING 通知（best-effort，
            // 失败不影响已创建的 PR 事实；DIFF_FIRST 手动建 MR 同样受益）
            if (qualityGates != null) {
                try {
                    qualityGates.onPullRequestCreated(postCreateMr);
                } catch (RuntimeException failure) {
                    log.warn("post-create quality gate hooks failed for MR {}", mr.getId(), failure);
                }
            }
            MergeRequestEntity gateReady = inTransaction(() -> {
                MergeRequestEntity current = mergeRequestMapper.selectByIdForUpdate(mr.getId());
                if (current == null) {
                    return postCreateMr;
                }
                refreshQualityGate(current);
                return current;
            });
            publishUpdated(gateReady);
            publishMergeRequestCard(resolvedClaim.task(), gateReady);
            return summary(gateReady);
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
        if (developmentGuard != null) {
            developmentGuard.requireBranchWritable(projectId, repositoryId, worktree.getSourceBranch(),
                    "DIFF_DELIVERY_BLOCKED_BY_OPEN_MR",
                    "当前工作分支存在未合并的 MR，不能继续进行 Diff 交付");
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
                .ne(MergeRequestEntity::getStatus, "MERGED").orderByDesc(MergeRequestEntity::getProviderUpdatedAt)
                .orderByDesc(MergeRequestEntity::getCreatedAt)
                .last("LIMIT 1"));
        if (existing != null && existing.getStatus() != null && (!"OPEN".equals(existing.getStatus())
                || !request.getTargetBranch().equals(existing.getTargetBranch()))) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_BRANCH_LOCKED_BY_OPEN_MR",
                    "该工作分支已有未合并的 MR，不能创建或更新新的 MR",
                    List.of(branchLockDetails(existing)));
        }
        requireTaskReadyForMr(task, worktree);
        // 已完成的 MR_FIRST Task 重放本地 OPEN 镜像时，后续仍会以 GitHub 真实开放 PR 和
        // 相同 head 校验为准。不要因为目标分支后来推进而让已创建 MR 的幂等查询错误地要求
        // 重跑 Dry Run；如果远端实际上已关闭，reconcile 会关闭镜像并重新领取，此时必须走当前门禁。
        boolean completedReplayCandidate = "MR_FIRST".equals(task.getDeliveryMode())
                && "SUCCEEDED".equals(task.getStatus()) && existing != null
                && sameCommit(worktree.getHeadCommit(), existing.getHeadCommit());
        if (existing != null && existing.getStatus() != null
                && !sameCommit(worktree.getHeadCommit(), existing.getHeadCommit())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_BRANCH_LOCKED_BY_OPEN_MR",
                    "该工作分支已有未合并的 MR，不能继续推送新的提交",
                    List.of(branchLockDetails(existing)));
        }
        if (existing == null && sameCommit(worktree.getHeadCommit(), targetCommit)) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_NO_CHANGES",
                    "源分支与目标分支当前提交相同，没有可创建 MR 的变更");
        }
        if (!completedReplayCandidate) {
            requirePreflightGates().requireReady(task, worktree, request.getRepositoryId(), request.getTargetBranch(), targetCommit);
        }
        // 仅在本地、任务状态与预检均通过后才取得 GitHub 上下文；后续仍必须查询远端核验
        // OPEN 镜像，不能把本地记录当作创建成功事实。
        GitHubRepositoryEntity githubRepository = requireGitHubRepository(projectId, request.getRepositoryId());
        GitHubInstallationEntity installation = requireInstallation(githubRepository);
        if (existing != null && worktree.getHeadCommit().equals(existing.getHeadCommit())) {
            return new CreateClaim(task, worktree, githubRepository, installation, request, null, null, existing, targetCommit);
        }
        if (existing != null) {
            // 已有 open MR 且 headCommit 不同：推送新 commit 后更新已有 MR，不新建 PR，也不走 delivery operation
            return new CreateClaim(task, worktree, githubRepository, installation, request, null, null, existing, targetCommit);
        }
        if (deliveryOperationMapper == null) {
            return new CreateClaim(task, worktree, githubRepository, installation, request, null, null, null, targetCommit);
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
                    request, operation, null, completed, targetCommit);
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
        return new CreateClaim(task, worktree, githubRepository, installation, request, operation, token, null, targetCommit);
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
                claim.githubRepository(), claim.installation(), claim.request(), claim.operation(), claim.token(), refreshed,
                claim.targetCommit());
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
        log.info("branch push starting projectId={} taskId={} repositoryId={} branch={} mode={} expectedHeadCommit={}",
                task.getProjectId(), task.getId(), repositoryId, worktree.getSourceBranch(), mode, worktree.getHeadCommit());
        for (int attempt = 1; attempt <= WORKER_PUSH_MAX_ATTEMPTS; attempt++) {
            String grantId = credentialService.generateGrant(installation.getTeamId(), task.getProjectId(),
                    installation.getProviderInstallationId(), fullName, worktree.getSourceBranch(),
                    worktree.getHeadCommit(), GitCredentialPurpose.PUSH);
            try {
                WorkerGitPushResponse pushed = workerClient.pushWorkspaceBranch(task.getWorkspaceId(), repositoryId,
                        new WorkerGitPushRequest().setExpectedHeadCommit(worktree.getHeadCommit())
                                .setCredentialGrantId(grantId));
                if (pushed == null || !pushed.isVerified() || !worktree.getHeadCommit().equals(pushed.getHeadCommit())) {
                    throw new ApiException(HttpStatus.CONFLICT, "WORKER_PUSH_VERIFICATION_FAILED",
                            "Sandbox Worker push verification failed or HEAD mismatch");
                }
                log.info("branch push verified projectId={} taskId={} repositoryId={} branch={} headCommit={} attempt={}",
                        task.getProjectId(), task.getId(), repositoryId, worktree.getSourceBranch(),
                        pushed.getHeadCommit(), attempt);
                return;
            } catch (ApiException failure) {
                if ("WORKER_PUSH_VERIFICATION_FAILED".equals(failure.code())) {
                    throw failure;
                }
                if (!isRetryableWorkerPush(failure) || attempt >= WORKER_PUSH_MAX_ATTEMPTS) {
                    log.warn("branch push failed projectId={} taskId={} repositoryId={} branch={} status={} code={} attempt={}",
                            task.getProjectId(), task.getId(), repositoryId, worktree.getSourceBranch(),
                            failure.status(), failure.code(), attempt);
                    throw new ApiException(failure.status(), "WORKER_PUSH_FAILED",
                            "Failed to push branch via Sandbox Worker");
                }
                sleepBeforeWorkerPushRetry(attempt, failure.code());
            }
        }
        throw new IllegalStateException("Worker push retry loop unexpectedly completed");
    }

    private boolean isRetryableWorkerPush(ApiException failure) {
        return switch (failure.code()) {
            case "SANDBOX_WORKER_UNAVAILABLE", "GIT_REMOTE_NETWORK_FAILED", "GIT_REMOTE_RATE_LIMITED",
                    "GIT_COMMAND_TIMEOUT" -> true;
            default -> false;
        };
    }

    private void sleepBeforeWorkerPushRetry(int attempt, String code) {
        long backoff = Math.min(2_000L, WORKER_PUSH_INITIAL_BACKOFF_MILLIS * (1L << (attempt - 1)));
        long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(Math.max(1L, backoff / 4));
        log.warn("branch push retrying attempt={} code={} backoffMs={}", attempt, code, backoff + jitter);
        try {
            Thread.sleep(backoff + jitter);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WORKER_PUSH_RETRY_INTERRUPTED", "代码推送重试被中断");
        }
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
        // 远端创建后、MR 与检查落库前再次以领取时冻结的 target commit 核验预检证据。
        // 此处不调用 GitHub/Worker，避免持有数据库事务时执行外部 HTTP 调用。
        PreflightGateService.PreflightEvidence preflight = requirePreflightGates().requireEvidence(claim.task(),
                claim.worktree(), claim.request().getRepositoryId(), claim.request().getTargetBranch(), claim.targetCommit());
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
        }
        // 同一 MR 的投影必须串行化：质量检查表保留 attempt 历史，不能以全局唯一键覆盖。
        // 新建 MR 已经落库，已有 MR 亦在此处统一锁定，再执行 select-then-insert 幂等判断。
        mr = mergeRequestMapper.selectByIdForUpdate(mr.getId());
        if (mr == null) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_FINALIZATION_CONTEXT_LOST",
                    "Pull Request local mirror disappeared during finalization");
        }
        projectPreflightChecks(mr, preflight);
        refreshQualityGate(mr);
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
        addRepositoryContext(content, task, mr.getProjectRepositoryId());
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

    private void addRepositoryContext(Map<String, Object> content, TaskEntity task, UUID repositoryId) {
        if (repositoryContextService == null || content == null || task == null) return;
        try {
            content.put("repositoryMappings", repositoryContextService.allRepositories(task));
            content.put("currentRepositoryPaths", repositoryId == null
                    ? List.of() : repositoryContextService.pathsForRepositories(task, List.of(repositoryId)));
        } catch (RuntimeException failure) {
            log.warn("repository context omitted from MR card taskId={} mrId={}: {}",
                    task.getId(), content.get("repositoryId"), failure.getMessage());
            content.put("repositoryMappings", List.of());
            content.put("currentRepositoryPaths", List.of());
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
                               String token, MergeRequestEntity existing, String targetCommit) {
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
                              GitHubInstallationEntity installation, String operationId, boolean alreadyCompleted,
                              boolean alreadyInProgress) {
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
        review.setReviewerName(reviewerName(userId));
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
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MergeRequestReviewEntity review = new MergeRequestReviewEntity();
        review.setId(UuidV7.next());
        review.setMergeRequestId(mr.getId());
        review.setReviewKind("HUMAN");
        review.setReviewerUserId(userId);
        review.setReviewerName(reviewerName(userId));
        review.setDecision("REJECTED");
        review.setSummary(reason);
        review.setReviewedAt(now);
        review.setCreatedAt(now);
        reviewMapper.insert(review);
        writeCheck(mr, "CQ_PLUS_ONE", "FAILED", "cq_rejection", reason, now);
        refreshQualityGate(mr);
        publishUpdated(mr);
        return toSummary(mr, groupIdsByMr(List.of(mr)).getOrDefault(mr.getId(), List.of()), qualityGate(mr),
                mrWebUrl(mr));
    }

    /**
     * 受理真实 GitHub 合并。数据库只在认领阶段持有短事务，慢速网络调用放入后台执行。
     * 测试环境未注入执行器时保留同步执行，便于维持服务层单元测试的确定性。
     */
    public MergeRequestSummaryResponse merge(UUID projectId, UUID mergeRequestId, UUID userId) {
        projectAccess.requireProjectAdmin(projectId, userId);
        MergeClaim claim = inTransaction(() -> claimMerge(projectId, mergeRequestId));
        if (claim.alreadyCompleted() || claim.alreadyInProgress()) {
            return summary(claim.mergeRequest());
        }
        boolean synchronous = mergeExecutor == null;
        Runnable operation = () -> executeMerge(projectId, mergeRequestId, claim, synchronous);
        if (mergeExecutor != null) {
            try {
                mergeExecutor.execute(operation);
            } catch (RejectedExecutionException rejected) {
                inTransaction(() -> failMerge(mergeRequestId, claim.operationId()));
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MERGE_EXECUTOR_UNAVAILABLE",
                        "合并任务当前排队已满，请稍后重试");
            }
            return summary(claim.mergeRequest());
        }
        operation.run();
        return summary(inTransaction(() -> mergeRequestMapper.selectById(mergeRequestId)));
    }

    private void executeMerge(UUID projectId, UUID mergeRequestId, MergeClaim claim, boolean propagateFailure) {
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
        } catch (RuntimeException failure) {
            MergeRequestEntity failed = inTransaction(() -> failMerge(mergeRequestId, claim.operationId()));
            if (failed != null) {
                publishUpdated(failed);
            }
            log.warn("GitHub merge failed asynchronously, projectId={}, mergeRequestId={}, operationId={}: {}",
                    projectId, mergeRequestId, claim.operationId(), failure.getMessage());
            if (propagateFailure) {
                throw failure;
            }
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
            return new MergeClaim(mr, githubRepository, installation, mr.getMergeOperationId(), true, false);
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
            return new MergeClaim(mr, githubRepository, installation, mr.getMergeOperationId(), false, true);
        }
        String operationId = mr.getMergeOperationId();
        if (operationId == null || operationId.isBlank()) {
            operationId = UuidV7.next().toString();
        }
        mr.setMergeOperationId(operationId);
        mr.setMergeOperationStatus("RUNNING");
        mr.setMergeLeaseExpiresAt(now.plus(MERGE_OPERATION_LEASE));
        mergeRequestMapper.updateById(mr);
        return new MergeClaim(mr, githubRepository, installation, operationId, false, false);
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

    private MergeRequestEntity failMerge(UUID mergeRequestId, String operationId) {
        MergeRequestEntity current = mergeRequestMapper.selectByIdForUpdate(mergeRequestId);
        if (current == null || !operationId.equals(current.getMergeOperationId())
                || "COMPLETED".equals(current.getMergeOperationStatus())) {
            return current;
        }
        current.setMergeOperationStatus("FAILED");
        current.setMergeLeaseExpiresAt(null);
        mergeRequestMapper.updateById(current);
        return current;
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
     * 将 MR 创建前已经核验的真实事实投影到 MR 检查列表。来源中包含证据 ID，重放同一
     * 远端创建操作时不会重复写入；后续新的 Dry Run/CQ 仍会形成新的 attempt。
     */
    private void projectPreflightChecks(MergeRequestEntity mr, PreflightGateService.PreflightEvidence evidence) {
        DryRunEntity dryRun = evidence.dryRun();
        PreflightCqReviewEntity cq = evidence.cqReview();
        writePreflightCheckIfAbsent(mr, "DRY_RUN", "PREFLIGHT_DRY_RUN:" + dryRun.getId(), Map.of(
                "dryRunId", dryRun.getId().toString(), "sourceCommit", dryRun.getHeadCommit(),
                "targetBranch", dryRun.getTargetBranch(), "targetCommit", dryRun.getResolvedTargetCommit()));
        Map<String, Object> cqSummary = new LinkedHashMap<>();
        cqSummary.put("dryRunId", dryRun.getId().toString());
        cqSummary.put("cqReviewId", cq.getId().toString());
        cqSummary.put("reviewerUserId", cq.getReviewerUserId().toString());
        cqSummary.put("sourceCommit", cq.getSourceCommit());
        cqSummary.put("targetBranch", cq.getTargetBranch());
        cqSummary.put("targetCommit", cq.getTargetCommit());
        writePreflightCheckIfAbsent(mr, "CQ_PLUS_ONE", "PREFLIGHT_CQ_PLUS_ONE:" + cq.getId(), cqSummary);
    }

    private void writePreflightCheckIfAbsent(MergeRequestEntity mr, String checkType, String source,
                                              Map<String, Object> summary) {
        QualityCheckResultEntity existing = qualityCheckMapper.selectOne(Wrappers.<QualityCheckResultEntity>lambdaQuery()
                .eq(QualityCheckResultEntity::getMergeRequestId, mr.getId())
                .eq(QualityCheckResultEntity::getCheckType, checkType)
                .eq(QualityCheckResultEntity::getCommitSha, mr.getHeadCommit())
                .eq(QualityCheckResultEntity::getSource, source).last("LIMIT 1"));
        if (existing != null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        QualityCheckResultEntity check = new QualityCheckResultEntity();
        check.setId(UuidV7.next());
        check.setMergeRequestId(mr.getId());
        check.setCheckType(checkType);
        check.setAttemptNo(nextAttemptNo(mr.getId(), checkType, mr.getHeadCommit()));
        check.setStatus("PASSED");
        check.setCommitSha(mr.getHeadCommit());
        check.setSource(source);
        check.setSummary(summary);
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

    private Map<String, Object> branchLockDetails(MergeRequestEntity mr) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mergeRequestId", mr.getId());
        details.put("providerNumber", mr.getProviderNumber());
        details.put("status", mr.getStatus());
        details.put("sourceBranch", mr.getSourceBranch());
        return details;
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
        // MR 状态变化会直接改变 source branch 是否可继续开发；前端收到后应重新查询分支列表，
        // 不要仅凭事件 payload 推断多仓库 Workspace 的聚合状态。
        Map<String, Object> branchPayload = new HashMap<>();
        branchPayload.put("projectId", repo == null ? null : repo.getProjectId());
        branchPayload.put("repositoryId", mr.getProjectRepositoryId());
        branchPayload.put("sourceBranch", mr.getSourceBranch());
        branchPayload.put("mergeRequestId", mr.getId());
        branchPayload.put("status", mr.getStatus());
        branchPayload.put("developmentStatus", "MERGED".equals(mr.getStatus()) ? "MERGED" : "LOCKED_BY_OPEN_MR");
        branchPayload.put("canContinueDevelopment", "MERGED".equals(mr.getStatus()));
        eventService.publish(repo == null ? null : repo.getProjectId(), null, "work-branch.updated",
                mr.getProjectRepositoryId() + ":" + mr.getSourceBranch(), branchPayload);
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

    /**
     * Builds non-persistent MR rows for Task worktrees that are ready for a user-triggered
     * Pull Request creation. The database remains the source of truth for real MR rows; these
     * rows are deterministic projections only and therefore never receive a database status.
     */
    private List<MergeRequestSummaryResponse> placeholderMergeRequests(UUID projectId, UUID repositoryId,
                                                                        UUID requirementGroupId,
                                                                        String taskRequiredStatus,
                                                                        Set<UUID> visibleGroupIds) {
        List<WorkspaceRepositoryEntity> worktrees = workspaceRepositoryMapper.selectByProject(projectId, repositoryId);
        if (worktrees == null || worktrees.isEmpty()) {
            return List.of();
        }

        List<UUID> workspaceIds = worktrees.stream().map(WorkspaceRepositoryEntity::getWorkspaceId)
                .filter(Objects::nonNull).distinct().toList();
        if (workspaceIds.isEmpty()) {
            return List.of();
        }
        QueryWrapper<TaskEntity> taskQuery = Wrappers.<TaskEntity>query()
                .eq("project_id", projectId).in("workspace_id", workspaceIds)
                .eq(requirementGroupId != null, "requirement_group_id", requirementGroupId);
        if (visibleGroupIds != null) {
            if (visibleGroupIds.isEmpty()) return List.of();
            taskQuery.in("requirement_group_id", visibleGroupIds);
        }
        // 待创建占位只代表已经完成交付、可以进入 MR 前门禁的任务。
        // 默认列表和显式 PENDING_CREATE 查询都必须排除仍在开发/等待交付确认的 Task。
        if ("WAITING_PREFLIGHT".equalsIgnoreCase(taskRequiredStatus)) {
            taskQuery.and(wrapper -> wrapper.eq("status", "WAITING_PREFLIGHT")
                    .or().eq("status", "SUCCEEDED"));
        } else if (taskRequiredStatus != null) {
            taskQuery.eq("status", taskRequiredStatus);
        }
        List<TaskEntity> tasks = taskMapper.selectList(taskQuery);
        if (tasks == null) tasks = List.of();
        Map<UUID, TaskEntity> taskById = new HashMap<>();
        for (TaskEntity task : tasks) {
            if (task == null || task.getWorkspaceId() == null) continue;
            if (!matchesPlaceholderTaskStatus(taskRequiredStatus, task.getStatus())) continue;
            if (requirementGroupId != null && !requirementGroupId.equals(task.getRequirementGroupId())) continue;
            if (task.getId() != null) taskById.put(task.getId(), task);
        }
        if (taskById.isEmpty()) {
            return List.of();
        }

        // A Workspace is provisioned with every project repository, but a Task's AI
        // changes only the repositories it actually touched. Use delivered Diff rows
        // as the repository-level evidence instead of applying the newest Task to every
        // worktree in the Workspace.
        List<DiffEntity> deliveredDiffs = diffMapper == null
                ? List.of()
                : diffMapper.selectList(Wrappers.<DiffEntity>lambdaQuery()
                .eq(DiffEntity::getProjectId, projectId)
                .in(DiffEntity::getTaskId, taskById.keySet())
                .eq(DiffEntity::getStatus, "ACCEPTED")
                .in(DiffEntity::getDeliveryStatus, "PUSHED", "MR_CREATED"));
        if (deliveredDiffs == null || deliveredDiffs.isEmpty()) {
            return List.of();
        }
        Set<String> deliveredTaskRepositoryKeys = deliveredDiffs.stream()
                .filter(Objects::nonNull)
                .filter(diff -> diff.getTaskId() != null && diff.getProjectRepositoryId() != null)
                .map(diff -> diff.getTaskId() + "|" + diff.getProjectRepositoryId()
                        + "|" + (diff.getSourceBranch() == null ? "" : diff.getSourceBranch()))
                .collect(Collectors.toSet());

        Set<UUID> projectRepositoryIds = worktrees.stream().map(WorkspaceRepositoryEntity::getProjectRepositoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        if (projectRepositoryIds.isEmpty()) {
            return List.of();
        }
        List<MergeRequestEntity> existing = mergeRequestMapper.selectList(
                Wrappers.<MergeRequestEntity>query()
                        .in("project_repository_id", projectRepositoryIds)
                        .ne("status", "PENDING_CREATE")
                        .ne("status", "MERGED")
                        .ne("status", "CLOSED"));
        if (existing == null) existing = List.of();
        Set<String> existingKeys = existing.stream()
                .filter(Objects::nonNull)
                .filter(mr -> mr.getProjectRepositoryId() != null && mr.getSourceBranch() != null)
                .map(mr -> branchKey(mr.getProjectRepositoryId(), mr.getSourceBranch()))
                .collect(Collectors.toSet());

        List<ProjectRepositoryEntity> repositoryRows = projectRepositoryMapper.selectBatchIds(projectRepositoryIds);
        if (repositoryRows == null) repositoryRows = List.of();
        Map<UUID, ProjectRepositoryEntity> repositories = repositoryRows.stream()
                .collect(Collectors.toMap(ProjectRepositoryEntity::getId, Function.identity(), (left, right) -> left));
        Map<String, PlaceholderCandidate> candidatesByBranch = new HashMap<>();
        for (WorkspaceRepositoryEntity worktree : worktrees) {
            if (worktree.getProjectRepositoryId() == null
                    || worktree.getSourceBranch() == null || worktree.getSourceBranch().isBlank()
                    || worktree.getHeadCommit() == null || worktree.getHeadCommit().isBlank()) {
                continue;
            }
            // 工作树 HEAD 仍停留在创建时的基线提交时，源分支没有可创建 MR 的新增变更。
            // 这种记录可能因任务已进入 WAITING_PREFLIGHT/SUCCEEDED 而存在，但不能注入
            // PENDING_CREATE 占位，否则前端会展示一个永远无法创建的 MR 候选。
            if (sameCommit(worktree.getHeadCommit(), worktree.getBaseCommit())) {
                continue;
            }
            TaskEntity task = taskById.values().stream()
                    .filter(candidate -> Objects.equals(candidate.getWorkspaceId(), worktree.getWorkspaceId()))
                    .filter(candidate -> deliveredTaskRepositoryKeys.contains(candidate.getId() + "|"
                            + worktree.getProjectRepositoryId() + "|" + worktree.getSourceBranch()))
                    .max(this::compareTasksForPlaceholder)
                    .orElse(null);
            if (task == null) {
                continue;
            }
            String key = branchKey(worktree.getProjectRepositoryId(), worktree.getSourceBranch());
            candidatesByBranch.merge(key, new PlaceholderCandidate(task, worktree),
                    this::newerPlaceholderCandidate);
        }

        List<MergeRequestSummaryResponse> result = new ArrayList<>();
        for (Map.Entry<String, PlaceholderCandidate> entry : candidatesByBranch.entrySet()) {
            if (existingKeys.contains(entry.getKey())) continue;
            PlaceholderCandidate candidate = entry.getValue();
            TaskEntity task = candidate.task();
            WorkspaceRepositoryEntity worktree = candidate.worktree();

            ProjectRepositoryEntity repository = repositories.get(worktree.getProjectRepositoryId());
            String targetBranch = worktree.getBaseRef();
            if (targetBranch == null || targetBranch.isBlank()) {
                targetBranch = repository == null ? null : repository.getDefaultBranch();
            }
            if (targetBranch == null || targetBranch.isBlank()) continue;

            MergeRequestSummaryResponse row = new MergeRequestSummaryResponse();
            row.setId(placeholderMrId(task.getId(), worktree.getProjectRepositoryId()));
            row.setRepositoryId(id(worktree.getProjectRepositoryId()));
            row.setGroupIds(List.of());
            row.setProvider("GITHUB");
            row.setNumber(0L);
            row.setSourceBranch(worktree.getSourceBranch());
            row.setTargetBranch(targetBranch);
            row.setStatus("PENDING_CREATE");
            row.setHeadCommit(worktree.getHeadCommit());
            row.setMergeable(null);
            row.setMergeableState(null);
            // A list read must remain read-only and must not refresh Git stores or call GitHub.
            // The dedicated Preflight endpoint supplies the live gate details for this Task.
            row.setQualityGate(new QualityGateResponse("PENDING", List.of()));
            row.setTitle(task.getTitle() == null || task.getTitle().isBlank()
                    ? worktree.getSourceBranch() + " -> " + targetBranch : task.getTitle());
            row.setWebUrl(null);
            row.setCreatedAt(iso(task.getUpdatedAt() == null ? task.getCreatedAt() : task.getUpdatedAt()));
            row.setTaskId(id(task.getId()));
            row.setCreateMode(placeholderCreateMode(task));
            result.add(row);
        }
        result.sort(Comparator.comparing(MergeRequestSummaryResponse::getId,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result;
    }

    private Set<UUID> visibleGroupIds(UUID projectId, UUID userId) {
        if (groupService == null) {
            return Set.of();
        }
        return new HashSet<>(groupService.visibleGroupIds(projectId, userId));
    }

    private List<UUID> visibleTaskIds(UUID projectId, Set<UUID> visibleGroupIds) {
        if (visibleGroupIds == null || visibleGroupIds.isEmpty()) {
            return List.of();
        }
        return taskMapper.selectList(Wrappers.<TaskEntity>query()
                        .eq("project_id", projectId)
                        .in("requirement_group_id", visibleGroupIds))
                .stream().map(TaskEntity::getId).filter(Objects::nonNull).toList();
    }

    private PlaceholderCandidate newerPlaceholderCandidate(PlaceholderCandidate left,
                                                            PlaceholderCandidate right) {
        if (Objects.equals(left.task().getId(), right.task().getId())) {
            return newerWorktree(left, right);
        }
        TaskEntity newerTask = newerPlaceholderTask(left.task(), right.task());
        if (newerTask == right.task()) return right;
        if (newerTask == left.task()) return left;
        return newerWorktree(left, right);
    }

    private TaskEntity newerPlaceholderTask(TaskEntity left, TaskEntity right) {
        LocalDateTime leftTime = left.getUpdatedAt() == null ? left.getCreatedAt() : left.getUpdatedAt();
        LocalDateTime rightTime = right.getUpdatedAt() == null ? right.getCreatedAt() : right.getUpdatedAt();
        if (leftTime == null && rightTime == null) {
            UUID leftId = left.getId();
            UUID rightId = right.getId();
            if (leftId == null) return right;
            if (rightId == null) return left;
            return rightId.compareTo(leftId) >= 0 ? right : left;
        }
        if (leftTime == null) return right;
        if (rightTime == null) return left;
        if (rightTime.isAfter(leftTime)) return right;
        if (rightTime.isBefore(leftTime)) return left;
        UUID leftId = left.getId();
        UUID rightId = right.getId();
        if (leftId == null) return right;
        if (rightId == null) return left;
        return rightId.compareTo(leftId) >= 0 ? right : left;
    }

    private PlaceholderCandidate newerWorktree(PlaceholderCandidate left, PlaceholderCandidate right) {
        LocalDateTime leftTime = left.worktree().getUpdatedAt() == null
                ? left.worktree().getCreatedAt() : left.worktree().getUpdatedAt();
        LocalDateTime rightTime = right.worktree().getUpdatedAt() == null
                ? right.worktree().getCreatedAt() : right.worktree().getUpdatedAt();
        if (leftTime == null) return right;
        if (rightTime == null) return left;
        if (rightTime.isAfter(leftTime)) return right;
        if (rightTime.isBefore(leftTime)) return left;
        String leftHead = left.worktree().getHeadCommit() == null ? "" : left.worktree().getHeadCommit();
        String rightHead = right.worktree().getHeadCommit() == null ? "" : right.worktree().getHeadCommit();
        int headComparison = rightHead.compareTo(leftHead);
        if (headComparison != 0) return headComparison > 0 ? right : left;
        String leftBase = left.worktree().getBaseRef() == null ? "" : left.worktree().getBaseRef();
        String rightBase = right.worktree().getBaseRef() == null ? "" : right.worktree().getBaseRef();
        int baseComparison = rightBase.compareTo(leftBase);
        if (baseComparison != 0) return baseComparison > 0 ? right : left;
        UUID leftWorkspace = left.worktree().getWorkspaceId();
        UUID rightWorkspace = right.worktree().getWorkspaceId();
        if (leftWorkspace == null) return right;
        if (rightWorkspace == null) return left;
        return rightWorkspace.compareTo(leftWorkspace) >= 0 ? right : left;
    }

    private boolean matchesPlaceholderTaskStatus(String requiredStatus, String actualStatus) {
        if (requiredStatus == null) return true;
        if ("WAITING_PREFLIGHT".equalsIgnoreCase(requiredStatus)) {
            return "WAITING_PREFLIGHT".equalsIgnoreCase(actualStatus)
                    || "SUCCEEDED".equalsIgnoreCase(actualStatus);
        }
        return requiredStatus.equalsIgnoreCase(actualStatus);
    }

    private String placeholderCreateMode(TaskEntity task) {
        if (task == null || task.getDeliveryMode() == null) return "UNKNOWN";
        if ("MR_FIRST".equalsIgnoreCase(task.getDeliveryMode())) return "SYSTEM";
        if ("DIFF_FIRST".equalsIgnoreCase(task.getDeliveryMode())) return "MANUAL";
        return "UNKNOWN";
    }

    private record PlaceholderCandidate(TaskEntity task, WorkspaceRepositoryEntity worktree) { }

    private int compareTasksForPlaceholder(TaskEntity left, TaskEntity right) {
        TaskEntity newer = newerPlaceholderTask(left, right);
        return newer == right ? -1 : newer == left ? 1 : 0;
    }

    private String branchKey(UUID repositoryId, String sourceBranch) {
        return repositoryId + "|" + sourceBranch;
    }

    private String reviewerName(UUID userId) {
        if (userMapper == null || userId == null) {
            return null;
        }
        UserEntity user = userMapper.selectById(userId);
        return user == null ? null : user.getDisplayName();
    }

    private String placeholderMrId(UUID taskId, UUID repositoryId) {
        String base = "pending-mr:" + (taskId == null ? "null-task" : taskId)
                + ":" + (repositoryId == null ? "null-repo" : repositoryId);
        return UUID.nameUUIDFromBytes(base.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private int comparePlaceholderId(String left, String right) {
        try {
            return UUID.fromString(left).compareTo(UUID.fromString(right));
        } catch (RuntimeException ignored) {
            return 1;
        }
    }

    private MergeRequestSummaryResponse toSummary(MergeRequestEntity mr, List<String> groupIds,
                                                  QualityGateResponse gate, String webUrl) {
        MergeRequestSummaryResponse response = new MergeRequestSummaryResponse();
        response.setId(id(mr.getId()));
        response.setRepositoryId(id(mr.getProjectRepositoryId()));
        response.setGroupIds(groupIds);
        response.setProvider(mr.getProvider());
        response.setNumber(mr.getProviderNumber());
        response.setSourceBranch(mr.getSourceBranch());
        response.setTargetBranch(mr.getTargetBranch());
        response.setStatus(mr.getStatus());
        response.setMergeOperationStatus(mr.getMergeOperationStatus());
        response.setHeadCommit(mr.getHeadCommit());
        response.setMergeable(mr.getMergeable());
        response.setMergeableState(mr.getMergeableState());
        response.setQualityGate(gate);
        response.setTitle(mr.getTitle());
        response.setWebUrl(webUrl);
        response.setCreatedAt(iso(mr.getCreatedAt()));
        response.setTaskId(id(mr.getTaskId()));
        response.setCreateMode(inferCreateMode(mr));
        return response;
    }

    private String inferCreateMode(MergeRequestEntity mr) {
        if (mr == null) return "UNKNOWN";
        if (mr.getTaskId() != null && mr.getAuthorUserId() != null) return "MANUAL";
        if (mr.getTaskId() != null) return "SYSTEM";
        return "UNKNOWN";
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

    private String listCacheKey(Object... values) {
        return Arrays.stream(values)
                .map(value -> value == null ? "" : String.valueOf(value))
                .collect(Collectors.joining("\u001f"));
    }

    private MrPageData await(CompletableFuture<MrPageData> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("merge request list query interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("merge request list query failed", cause);
        }
    }

    private void trimListCache() {
        if (listCache.size() <= LIST_CACHE_MAX_ENTRIES) {
            return;
        }
        long now = System.currentTimeMillis();
        listCache.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
        while (listCache.size() > LIST_CACHE_MAX_ENTRIES) {
            String key = listCache.keys().hasMoreElements() ? listCache.keys().nextElement() : null;
            if (key == null || listCache.remove(key) == null) {
                break;
            }
        }
    }

    private record CachedMrPage(CompletableFuture<MrPageData> future, long expiresAt) { }

    private record MrPageData(List<MergeRequestSummaryResponse> data, PageMeta page) { }
}
