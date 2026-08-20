package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.DryRunResponse;
import qg.qgent.dto.MergeRequestPreflightResponse;
import qg.qgent.dto.MergeRequestSummaryResponse;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.MrPreflightRequestEntity;
import qg.qgent.entity.MrPreflightTaskEntity;
import qg.qgent.entity.PreflightCqReviewEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.MrPreflightRequestMapper;
import qg.qgent.mapper.MrPreflightTaskMapper;
import qg.qgent.mapper.PreflightCqReviewMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;

/**
 * 分支级 MR 预检申请：持久化“用户/交付事件申请创建 MR”这一业务流程事实，并统一
 * MR_FIRST 与 DIFF_FIRST 两条入口进入 Dry Run / CQ+1 / 自动创建真实 MR 的同一链路。
 * <p>
 * 幂等上下文是分支级：同一 {@code (projectRepositoryId, sourceBranch, targetBranch,
 * headCommit, targetCommit)} 的重复申请返回已有进行中的预检；head 变化形成新上下文。
 * 真实 MR 创建只由 CQ+1 通过后的内部自动化调用，本服务不直接创建 MR。
 */
@Service
@Slf4j
public class MrPreflightService {
    /** 预检进行中即视为分支锁定；终态（MR_CREATED/CQ_REJECTED/FAILED/STALE）自动解锁。 */
    private static final java.util.Set<String> PREFLIGHT_LOCKED_STATUSES = java.util.Set.of(
            "REQUESTED", "DRY_RUN_QUEUED", "DRY_RUN_RUNNING", "WAITING_CQ", "CREATING_MR");

    private final TaskMapper tasks;
    private final WorkspaceRepositoryMapper worktrees;
    private final ProjectRepositoryMapper repositories;
    private final MrPreflightRequestMapper preflightRequests;
    private final MrPreflightTaskMapper preflightTasks;
    private final MergeRequestMapper mergeRequests;
    private final DryRunMapper dryRuns;
    private final PreflightCqReviewMapper cqReviews;
    private final DiffMapper diffs;
    private final TestRunService testRuns;
    private final GitStoreSyncService gitStores;
    private final ProjectAccessService access;
    private final EventService events;
    private final TransactionTemplate transactions;

    public MrPreflightService(TaskMapper tasks, WorkspaceRepositoryMapper worktrees,
                              ProjectRepositoryMapper repositories, MrPreflightRequestMapper preflightRequests,
                              MrPreflightTaskMapper preflightTasks, MergeRequestMapper mergeRequests,
                              DryRunMapper dryRuns, PreflightCqReviewMapper cqReviews, DiffMapper diffs,
                              TestRunService testRuns, GitStoreSyncService gitStores,
                              ProjectAccessService access, EventService events, TransactionTemplate transactions) {
        this.tasks = tasks;
        this.worktrees = worktrees;
        this.repositories = repositories;
        this.preflightRequests = preflightRequests;
        this.preflightTasks = preflightTasks;
        this.mergeRequests = mergeRequests;
        this.dryRuns = dryRuns;
        this.cqReviews = cqReviews;
        this.diffs = diffs;
        this.testRuns = testRuns;
        this.gitStores = gitStores;
        this.access = access;
        this.events = events;
        this.transactions = transactions;
    }

    /**
     * 申请分支级 MR 预检：校验任务/仓库/工作树上下文与分支锁定，持久化申请事实（含覆盖任务），
     * 然后复用内部自动 Dry Run 启动测试。返回 {@code 202} 对应的预检状态，不创建真实 MR。
     *
     * @param taskId         触发申请的任务；分支级汇总可为任一已交付任务
     * @param repositoryId   项目仓库绑定ID
     * @param idempotencyKey 客户端幂等键；同一键返回同一预检
     */
    public MergeRequestPreflightResponse requestPreflight(UUID projectId, UUID userId, UUID taskId,
                                                          UUID repositoryId, String idempotencyKey) {
        access.requireProjectMember(projectId, userId);
        TaskEntity task = requireTask(projectId, taskId);
        if (task.getWorkspaceId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PREFLIGHT_WORKSPACE_MISSING", "Task 尚未准备 Workspace");
        }
        if (!isPreflightActionable(task)) {
            throw new ApiException(HttpStatus.CONFLICT, "PREFLIGHT_TASK_NOT_READY",
                    "Task 尚未处于可发起 MR 预检的稳定状态");
        }
        if (!userId.equals(task.getCreatedBy())) {
            access.requireProjectAdmin(projectId, userId);
        }
        ProjectRepositoryEntity repository = repositories.selectById(repositoryId);
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPOSITORY_NOT_IN_PROJECT",
                    "Repository is not bound to the current Project");
        }
        WorkspaceRepositoryEntity worktree = requireWorktree(task, repositoryId);
        if (worktree.getSourceBranch() == null || worktree.getSourceBranch().isBlank()
                || worktree.getHeadCommit() == null || worktree.getHeadCommit().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_BRANCH_NOT_PUSHED",
                    "工作分支必须先 commit 并 push 后才能发起 MR 预检");
        }
        requireBranchNotLocked(projectId, repositoryId, worktree.getSourceBranch());
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            MrPreflightRequestEntity byKey = preflightRequests.selectOne(Wrappers.<MrPreflightRequestEntity>query()
                    .eq("idempotency_key", idempotencyKey).last("LIMIT 1"));
            if (byKey != null && projectId.equals(byKey.getProjectId())) {
                ensureDryRun(projectId, task, worktree, repositoryId, byKey.getTargetBranch(), byKey);
                return toResponse(projectId, userId, byKey);
            }
        }
        String targetBranch = resolveTargetBranch(worktree, repository);
        // 目标分支 SHA 必须在短事务外解析（GitHub/Worker 外调），并作为预检上下文固定。
        String targetCommit = gitStores.refreshTargetBranch(projectId, repository, targetBranch);
        String contextHash = contextHash(repositoryId, worktree.getSourceBranch(), targetBranch,
                worktree.getHeadCommit(), targetCommit);
        MrPreflightRequestEntity existing = preflightRequests.selectByContextHash(contextHash);
        if (existing != null) {
            ensureDryRun(projectId, task, worktree, repositoryId, targetBranch, existing);
            return toResponse(projectId, userId, existing);
        }
        List<UUID> coveredTaskIds = new ArrayList<>(tasks.selectDeliveredTasksOnBranch(projectId,
                repositoryId, worktree.getSourceBranch()));
        if (!coveredTaskIds.contains(taskId)) {
            coveredTaskIds.add(0, taskId);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        MrPreflightRequestEntity request = new MrPreflightRequestEntity();
        request.setId(UuidV7.next());
        request.setProjectId(projectId);
        request.setTriggerTaskId(taskId);
        request.setProjectRepositoryId(repositoryId);
        request.setWorkspaceId(task.getWorkspaceId());
        request.setSourceBranch(worktree.getSourceBranch());
        request.setTargetBranch(targetBranch);
        request.setContextHash(contextHash);
        request.setHeadCommit(worktree.getHeadCommit());
        request.setTargetCommit(targetCommit);
        request.setStatus("REQUESTED");
        request.setRequestedBy(userId);
        request.setIdempotencyKey(idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey);
        request.setCreatedAt(now);
        request.setUpdatedAt(now);
        try {
            transactions.executeWithoutResult(status -> {
                preflightRequests.insert(request);
                for (UUID covered : coveredTaskIds) {
                    preflightTasks.insertLink(request.getId(), covered,
                            covered.equals(taskId) ? "TRIGGER" : "COVERED");
                }
            });
        } catch (DuplicateKeyException race) {
            MrPreflightRequestEntity claimed = preflightRequests.selectByContextHash(contextHash);
            if (claimed != null) {
                ensureDryRun(projectId, task, worktree, repositoryId, targetBranch, claimed);
                return toResponse(projectId, userId, claimed);
            }
            throw race;
        }
        publishPreflightUpdated(request);
        ensureDryRun(projectId, task, worktree, repositoryId, targetBranch, request);
        return toResponse(projectId, userId, request);
    }

    /**
     * 查询单条分支级预检申请（含实时推导的当前状态、覆盖任务/Diff、真实 MR）。
     */
    public MergeRequestPreflightResponse getPreflight(UUID projectId, UUID preflightId, UUID userId) {
        access.requireProjectMember(projectId, userId);
        MrPreflightRequestEntity request = preflightRequests.selectById(preflightId);
        if (request == null || !projectId.equals(request.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PREFLIGHT_NOT_FOUND", "预检申请不存在或不可见");
        }
        return toResponse(projectId, userId, request);
    }

    /**
     * 按 Task 获取全部仓库的分支级预检状态（多仓库任务逐仓库返回）。
     */
    public List<MergeRequestPreflightResponse> getTaskPreflight(UUID projectId, UUID taskId, UUID userId) {
        access.requireProjectMember(projectId, userId);
        TaskEntity task = requireTask(projectId, taskId);
        if (task.getWorkspaceId() == null) {
            return List.of();
        }
        List<MergeRequestPreflightResponse> result = new ArrayList<>();
        for (WorkspaceRepositoryEntity worktree : worktrees.selectByWorkspace(task.getWorkspaceId())) {
            MrPreflightRequestEntity request = preflightRequests.selectLatestByTaskAndRepository(projectId,
                    taskId, worktree.getProjectRepositoryId());
            if (request != null) {
                result.add(toResponse(projectId, userId, request));
            }
        }
        return result;
    }

    /**
     * 重新预检：为 CQ 拒绝或失败的分支级预检创建全新 Dry Run，让绑定旧 Dry Run 的 CQ+1 失效，
     * 并把预检请求重置为 DRY_RUN_QUEUED。只有 CQ_REJECTED / FAILED 状态允许重试。
     */
    public MergeRequestPreflightResponse retryPreflight(UUID projectId, UUID preflightId, UUID userId) {
        access.requireProjectMember(projectId, userId);
        MrPreflightRequestEntity request = preflightRequests.selectByIdForUpdate(preflightId);
        if (request == null || !projectId.equals(request.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PREFLIGHT_NOT_FOUND", "预检申请不存在或不可见");
        }
        if (!"CQ_REJECTED".equals(request.getStatus()) && !"FAILED".equals(request.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "PREFLIGHT_RETRY_NOT_ALLOWED",
                    "只有 CQ 拒绝或失败的预检可以重新运行");
        }
        if (request.getDryRunId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PREFLIGHT_RETRY_NOT_ALLOWED", "预检尚未完成首次 Dry Run");
        }
        DryRunResponse dryRun = testRuns.retryPreflightDryRun(projectId, request.getDryRunId(), userId);
        if (dryRun == null) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DRY_RUN_DISPATCH_FAILED", "Dry Run 创建失败，请稍后重试");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        request.setDryRunId(UUID.fromString(dryRun.getId()));
        request.setStatus("DRY_RUN_QUEUED");
        request.setFailureCode(null);
        request.setFailureReason(null);
        request.setTargetCommit(dryRun.getTargetCommit() == null ? request.getTargetCommit() : dryRun.getTargetCommit());
        request.setUpdatedAt(now);
        preflightRequests.updateById(request);
        publishPreflightUpdated(request);
        return toResponse(projectId, userId, request);
    }

    /**
     * 恢复调度器调用：按当前 Dry Run / CQ 事实推进持久化状态并发布变更事件。
     * <p>
     * - 未分配 Dry Run：尝试复用自动 Dry Run（幂等）；
     * - Dry Run PASSED：进入 WAITING_CQ；
     * - Dry Run FAILED：进入 FAILED 并回填稳定失败码/原因；
     * - 真实 MR 已创建：进入 MR_CREATED。
     */
    public void reconcile(MrPreflightRequestEntity request) {
        if (request == null || request.getProjectId() == null) {
            return;
        }
        if (request.getMergeRequestId() != null) {
            updateStatus(request, "MR_CREATED", null, null);
            return;
        }
        if (request.getDryRunId() == null) {
            TaskEntity task = tasks.selectById(request.getTriggerTaskId());
            WorkspaceRepositoryEntity worktree = requireWorktreeOrNull(task, request.getProjectRepositoryId());
            if (task != null && worktree != null && worktree.getHeadCommit() != null) {
                ensureDryRun(request.getProjectId(), task, worktree, request.getProjectRepositoryId(),
                        request.getTargetBranch(), request);
            }
            return;
        }
        DryRunEntity dryRun = dryRuns.selectById(request.getDryRunId());
        if (dryRun == null) {
            return;
        }
        switch (dryRun.getStatus()) {
            case "PASSED" -> {
                PreflightCqReviewEntity cq = latestCq(dryRun.getId(), request.getHeadCommit(),
                        request.getTargetCommit(), request.getRequestedBy());
                if (cq != null && "APPROVED".equals(cq.getDecision())) {
                    updateStatus(request, "CREATING_MR", null, null);
                } else if (cq != null && "REJECTED".equals(cq.getDecision())) {
                    updateStatus(request, "CQ_REJECTED", null, null);
                } else {
                    updateStatus(request, "WAITING_CQ", null, null);
                }
            }
            case "FAILED" -> {
                Map<String, Object> report = dryRun.getReport();
                String code = report == null ? null : String.valueOf(report.get("failureCode"));
                String reason = report == null ? null : String.valueOf(report.get("message"));
                updateStatus(request, "FAILED", "null".equals(code) ? null : code,
                        "null".equals(reason) ? null : reason);
            }
            case "QUEUED" -> updateStatus(request, "DRY_RUN_QUEUED", null, null);
            case "RUNNING" -> updateStatus(request, "DRY_RUN_RUNNING", null, null);
            default -> { /* 保留既有状态，避免终态回退 */ }
        }
    }

    // ---------- 私有辅助 ----------

    /**
     * 真实 MR 创建成功后由编排器调用：把关联预检请求回填真实 MR ID 并推进到 MR_CREATED，
     * 同时发布 preflight.updated 事件供前端刷新。
     */
    public void markMrCreated(UUID projectId, UUID dryRunId, UUID mergeRequestId) {
        if (mergeRequestId == null) {
            return;
        }
        MrPreflightRequestEntity request = preflightRequests.selectOne(Wrappers.<MrPreflightRequestEntity>query()
                .eq("project_id", projectId)
                .eq("dry_run_id", dryRunId)
                .last("LIMIT 1"));
        if (request == null) {
            return;
        }
        if (mergeRequestId.equals(request.getMergeRequestId())) {
            return;
        }
        request.setMergeRequestId(mergeRequestId);
        request.setStatus("MR_CREATED");
        request.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        preflightRequests.updateById(request);
        publishPreflightUpdated(request);
    }

    private void ensureDryRun(UUID projectId, TaskEntity task, WorkspaceRepositoryEntity worktree,
                              UUID repositoryId, String targetBranch, MrPreflightRequestEntity request) {
        if (request.getDryRunId() != null) {
            return;
        }
        DryRunResponse response = testRuns.createAutomaticDryRun(projectId, task.getId(), repositoryId, targetBranch);
        if (response == null) {
            updateStatus(request, "FAILED", "DRY_RUN_DISPATCH_FAILED", "Dry Run 创建失败，请稍后重试");
            return;
        }
        UUID dryRunId = UUID.fromString(response.getId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        transactions.executeWithoutResult(status -> {
            MrPreflightRequestEntity current = preflightRequests.selectByIdForUpdate(request.getId());
            if (current != null && current.getDryRunId() == null) {
                current.setDryRunId(dryRunId);
                current.setStatus("DRY_RUN_QUEUED");
                current.setTargetCommit(response.getTargetCommit() == null ? current.getTargetCommit()
                        : response.getTargetCommit());
                current.setUpdatedAt(now);
                preflightRequests.updateById(current);
            }
        });
        // 同步内存中的申请对象，保证幂等复用路径返回的响应携带最新 dryRunId/状态。
        request.setDryRunId(dryRunId);
        request.setStatus("DRY_RUN_QUEUED");
        request.setTargetCommit(response.getTargetCommit() == null ? request.getTargetCommit()
                : response.getTargetCommit());
        request.setUpdatedAt(now);
        publishPreflightUpdated(preflightRequests.selectById(request.getId()));
    }

    private void updateStatus(MrPreflightRequestEntity request, String status, String failureCode, String failureReason) {
        if (status.equals(request.getStatus()) && java.util.Objects.equals(failureCode, request.getFailureCode())
                && java.util.Objects.equals(failureReason, request.getFailureReason())) {
            return;
        }
        request.setStatus(status);
        request.setFailureCode(failureCode);
        request.setFailureReason(failureReason);
        request.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        preflightRequests.updateById(request);
        publishPreflightUpdated(request);
    }

    private void publishPreflightUpdated(MrPreflightRequestEntity request) {
        if (request == null) {
            return;
        }
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("projectId", request.getProjectId());
        payload.put("preflightId", request.getId());
        payload.put("taskId", request.getTriggerTaskId());
        payload.put("repositoryId", request.getProjectRepositoryId());
        payload.put("status", request.getStatus());
        payload.put("dryRunId", request.getDryRunId());
        payload.put("sourceBranch", request.getSourceBranch());
        payload.put("targetBranch", request.getTargetBranch());
        events.publish(request.getProjectId(), null, "preflight.updated", request.getId().toString(), payload);
    }

    private MergeRequestPreflightResponse toResponse(UUID projectId, UUID userId, MrPreflightRequestEntity request) {
        MergeRequestPreflightResponse response = new MergeRequestPreflightResponse();
        response.setId(id(request.getId()));
        response.setTaskId(id(request.getTriggerTaskId()));
        response.setRepositoryId(id(request.getProjectRepositoryId()));
        response.setSourceBranch(request.getSourceBranch());
        response.setTargetBranch(request.getTargetBranch());
        response.setHeadCommit(request.getHeadCommit());
        response.setTargetCommit(request.getTargetCommit());
        response.setDryRunId(id(request.getDryRunId()));
        List<UUID> coveredTaskIds = preflightTasks.selectByPreflight(request.getId()).stream()
                .map(MrPreflightTaskEntity::getTaskId).toList();
        response.setCoveredTaskIds(coveredTaskIds.stream().map(this::id).toList());
        response.setCoveredDiffIds(diffs.selectDeliveredDiffIds(projectId, request.getProjectRepositoryId(),
                coveredTaskIds).stream().map(this::id).toList());
        response.setStatus(deriveStatus(request));
        response.setFailureCode(failureCode(request));
        response.setFailureReason(failureReason(request));
        response.setBranchLockStatus(branchLockStatus(projectId, request.getProjectRepositoryId(),
                request.getSourceBranch()));
        response.setMergeRequest(mergeRequest(projectId, request.getMergeRequestId()));
        return response;
    }

    /**
     * 从持久化 Dry Run / CQ 事实推导当前预检状态；已创建真实 MR 时优先展示 MR_CREATED。
     */
    private String deriveStatus(MrPreflightRequestEntity request) {
        if (request.getMergeRequestId() != null) {
            return "MR_CREATED";
        }
        if (request.getDryRunId() == null) {
            return "REQUESTED";
        }
        DryRunEntity dryRun = dryRuns.selectById(request.getDryRunId());
        if (dryRun == null) {
            return request.getStatus();
        }
        return switch (dryRun.getStatus()) {
            case "QUEUED" -> "DRY_RUN_QUEUED";
            case "RUNNING" -> "DRY_RUN_RUNNING";
            case "FAILED" -> "FAILED";
            case "PASSED" -> {
                PreflightCqReviewEntity cq = latestCq(dryRun.getId(), request.getHeadCommit(),
                        request.getTargetCommit(), request.getRequestedBy());
                if (cq != null && "REJECTED".equals(cq.getDecision())) {
                    yield "CQ_REJECTED";
                }
                if (cq != null && "APPROVED".equals(cq.getDecision())) {
                    yield "CREATING_MR";
                }
                yield "WAITING_CQ";
            }
            default -> request.getStatus();
        };
    }

    private String failureCode(MrPreflightRequestEntity request) {
        if (request.getFailureCode() != null) {
            return request.getFailureCode();
        }
        if (request.getDryRunId() != null) {
            DryRunEntity dryRun = dryRuns.selectById(request.getDryRunId());
            Map<String, Object> report = dryRun == null ? null : dryRun.getReport();
            Object code = report == null ? null : report.get("failureCode");
            if (code != null && !"null".equals(String.valueOf(code))) {
                return String.valueOf(code);
            }
        }
        return null;
    }

    private String failureReason(MrPreflightRequestEntity request) {
        if (request.getFailureReason() != null) {
            return request.getFailureReason();
        }
        if (request.getDryRunId() != null) {
            DryRunEntity dryRun = dryRuns.selectById(request.getDryRunId());
            Map<String, Object> report = dryRun == null ? null : dryRun.getReport();
            Object message = report == null ? null : report.get("message");
            if (message != null && !"null".equals(String.valueOf(message))) {
                return String.valueOf(message);
            }
        }
        return null;
    }

    private PreflightCqReviewEntity latestCq(UUID dryRunId, String sourceCommit, String targetCommit,
                                             UUID requesterId) {
        return cqReviews.selectOne(Wrappers.<PreflightCqReviewEntity>lambdaQuery()
                .eq(PreflightCqReviewEntity::getDryRunId, dryRunId)
                .eq(PreflightCqReviewEntity::getSourceCommit, sourceCommit)
                .eq(PreflightCqReviewEntity::getTargetCommit, targetCommit)
                .ne(requesterId != null, PreflightCqReviewEntity::getReviewerUserId, requesterId)
                .orderByDesc(PreflightCqReviewEntity::getReviewedAt)
                .orderByDesc(PreflightCqReviewEntity::getId).last("LIMIT 1"));
    }

    private String resolveTargetBranch(WorkspaceRepositoryEntity worktree, ProjectRepositoryEntity repository) {
        String target = worktree.getBaseRef();
        if (target == null || target.isBlank()) {
            target = repository == null ? null : repository.getDefaultBranch();
        }
        return gitStores.normalizeTargetBranch(target);
    }

    private void requireBranchNotLocked(UUID projectId, UUID repositoryId, String sourceBranch) {
        MergeRequestEntity open = mergeRequests.selectOne(Wrappers.<MergeRequestEntity>lambdaQuery()
                .eq(MergeRequestEntity::getProjectRepositoryId, repositoryId)
                .eq(MergeRequestEntity::getSourceBranch, sourceBranch)
                .ne(MergeRequestEntity::getStatus, "MERGED")
                .orderByDesc(MergeRequestEntity::getProviderUpdatedAt).orderByDesc(MergeRequestEntity::getCreatedAt)
                .last("LIMIT 1"));
        if (open != null && open.getStatus() != null && !"CLOSED".equals(open.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_BRANCH_LOCKED_BY_OPEN_MR",
                    "该工作分支已有未合并的 MR，不能发起新的预检申请",
                    List.of(Map.of("mergeRequestId", id(open.getId()), "status", open.getStatus())));
        }
    }

    private String branchLockStatus(UUID projectId, UUID repositoryId, String sourceBranch) {
        MergeRequestEntity open = mergeRequests.selectOne(Wrappers.<MergeRequestEntity>lambdaQuery()
                .eq(MergeRequestEntity::getProjectRepositoryId, repositoryId)
                .eq(MergeRequestEntity::getSourceBranch, sourceBranch)
                .ne(MergeRequestEntity::getStatus, "MERGED")
                .orderByDesc(MergeRequestEntity::getProviderUpdatedAt).orderByDesc(MergeRequestEntity::getCreatedAt)
                .last("LIMIT 1"));
        if (open != null && open.getStatus() != null && !"CLOSED".equals(open.getStatus())) {
            return "LOCKED_BY_OPEN_MR";
        }
        MrPreflightRequestEntity active = preflightRequests.selectOne(Wrappers.<MrPreflightRequestEntity>query()
                .eq("project_repository_id", repositoryId)
                .eq("source_branch", sourceBranch)
                .in("status", PREFLIGHT_LOCKED_STATUSES)
                .orderByDesc("created_at")
                .last("LIMIT 1"));
        return active != null ? "LOCKED_BY_PREFLIGHT" : "UNLOCKED";
    }

    private MergeRequestSummaryResponse mergeRequest(UUID projectId, UUID mergeRequestId) {
        if (mergeRequestId == null) {
            return null;
        }
        MergeRequestEntity mr = mergeRequests.selectById(mergeRequestId);
        ProjectRepositoryEntity repository = mr == null || mr.getProjectRepositoryId() == null
                ? null : repositories.selectById(mr.getProjectRepositoryId());
        if (mr == null || repository == null || !projectId.equals(repository.getProjectId())) {
            return null;
        }
        MergeRequestSummaryResponse summary = new MergeRequestSummaryResponse();
        summary.setId(id(mr.getId()));
        summary.setRepositoryId(id(mr.getProjectRepositoryId()));
        summary.setProvider(mr.getProvider());
        summary.setNumber(mr.getProviderNumber());
        summary.setSourceBranch(mr.getSourceBranch());
        summary.setTargetBranch(mr.getTargetBranch());
        summary.setStatus(mr.getStatus());
        summary.setHeadCommit(mr.getHeadCommit());
        summary.setMergeable(mr.getMergeable());
        summary.setMergeableState(mr.getMergeableState());
        summary.setTitle(mr.getTitle());
        summary.setTaskId(id(mr.getTaskId()));
        return summary;
    }

    private boolean isPreflightActionable(TaskEntity task) {
        return ("MR_FIRST".equals(task.getDeliveryMode())
                && ("WAITING_PREFLIGHT".equals(task.getStatus()) || "SUCCEEDED".equals(task.getStatus())))
                || ("DIFF_FIRST".equals(task.getDeliveryMode()) && "SUCCEEDED".equals(task.getStatus()));
    }

    private TaskEntity requireTask(UUID projectId, UUID taskId) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task 不存在或不可见");
        }
        return task;
    }

    private WorkspaceRepositoryEntity requireWorktree(TaskEntity task, UUID repositoryId) {
        WorkspaceRepositoryEntity worktree = requireWorktreeOrNull(task, repositoryId);
        if (worktree == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PREFLIGHT_REPOSITORY_NOT_IN_WORKSPACE",
                    "Task Workspace 不包含目标仓库");
        }
        return worktree;
    }

    private WorkspaceRepositoryEntity requireWorktreeOrNull(TaskEntity task, UUID repositoryId) {
        if (task == null || task.getWorkspaceId() == null) {
            return null;
        }
        return worktrees.selectByWorkspace(task.getWorkspaceId()).stream()
                .filter(value -> repositoryId.equals(value.getProjectRepositoryId())).findFirst().orElse(null);
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    /**
     * 生成分支级预检上下文唯一键。原始分支字段继续保存用于展示，数据库只对固定长度哈希建唯一索引。
     */
    static String contextHash(UUID repositoryId, String sourceBranch, String targetBranch,
                               String headCommit, String targetCommit) {
        String value = String.join("|", String.valueOf(repositoryId), sourceBranch, targetBranch,
                headCommit, targetCommit);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
