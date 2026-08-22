package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitDiff;
import qg.qgent.service.event.MrFirstPreflightRequestedDomainEvent;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Applies one Task-level review decision, then delivers each repository independently.
 */
@Service
@Slf4j
public class DiffReviewBatchService {
    /**
     * 批次交付租约时长；过期后可被重新领取（confirm 重试 / MR_FIRST 兜底扫描）。对 MrFirstDeliveryService 公开。
     */
    public static final Duration DELIVERY_LEASE = Duration.ofMinutes(30);
    private final DiffReviewBatchMapper batches;
    private final DiffMapper diffs;
    private final TaskMapper tasks;
    private final ProjectRepositoryMapper repositories;
    private final SandboxWorkerClient worker;
    private final MergeRequestService mergeRequests;
    private final ProjectAccessService access;
    private final EventService events;
    private final TransactionTemplate transactions;
    private final DiffSnapshotStorage snapshots;
    private final DiffDeliveryService deliveryService;
    private final MergeRequestMapper mergeRequestMapper;
    private final GitHubRepositoryMapper githubRepositories;
    private final NotificationService notificationService;
    /**
     * 预检等待卡片属于用户可见的群聊交互；发送失败不能改变已完成的 Commit/Push 事实。
     * 使用可选注入保持既有纯单元测试的构造器稳定。
     */
    private MessageService messageService;
    private OrchestratorAgentService orchestratorAgents;
    /** TASK_STATUS 卡片仓库映射；缺失时仅降级卡片字段，不影响交付。 */
    private TaskStatusRepositoryContextService repositoryContextService;
    /** 跨实例 Workspace 写租约；生产环境必须注入，纯 Mockito 构造器可不设置。 */
    private WorkspaceWriteLeaseService workspaceWriteLeases;
    /** commit/push 前的工作分支 MR 锁定门禁。 */
    private WorkBranchDevelopmentGuard developmentGuard;
    /** Optional in lightweight tests; production uses it to serialize review decisions with continuations. */
    private WorkspaceMapper workspaces;
    /** 交付确认前刷新各仓库不可变 baseRef 对应的远端目标分支。 */
    private WorkspaceRepositoryMapper workspaceRepositories;
    /** GitHub/Worker 目标分支同步；纯领域单测可不注入。 */
    private GitStoreSyncService gitStores;
    /** MR 前预检的进程内触发，不再经由 SSE eventType 桥接。 */
    private final ApplicationEventPublisher domainEvents;

    public DiffReviewBatchService(DiffReviewBatchMapper batches, DiffMapper diffs, TaskMapper tasks,
            ProjectRepositoryMapper repositories, SandboxWorkerClient worker,
            MergeRequestService mergeRequests, ProjectAccessService access, EventService events,
            ApplicationEventPublisher domainEvents, TransactionTemplate transactions, DiffSnapshotStorage snapshots, DiffDeliveryService deliveryService,
            MergeRequestMapper mergeRequestMapper, GitHubRepositoryMapper githubRepositories,
            NotificationService notificationService) {
        this.batches = batches;
        this.diffs = diffs;
        this.tasks = tasks;
        this.repositories = repositories;
        this.worker = worker;
        this.mergeRequests = mergeRequests;
        this.access = access;
        this.events = events;
        this.domainEvents = domainEvents;
        this.transactions = transactions;
        this.snapshots = snapshots;
        this.deliveryService = deliveryService;
        this.mergeRequestMapper = mergeRequestMapper;
        this.githubRepositories = githubRepositories;
        this.notificationService = notificationService;
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
    void setWorkspaceWriteLeases(WorkspaceWriteLeaseService workspaceWriteLeases) {
        this.workspaceWriteLeases = workspaceWriteLeases;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDevelopmentGuard(WorkBranchDevelopmentGuard developmentGuard) {
        this.developmentGuard = developmentGuard;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWorkspaceMapper(WorkspaceMapper workspaces) {
        this.workspaces = workspaces;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWorkspaceRepositoryMapper(WorkspaceRepositoryMapper workspaceRepositories) {
        this.workspaceRepositories = workspaceRepositories;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setGitStoreSyncService(GitStoreSyncService gitStores) {
        this.gitStores = gitStores;
    }

    public DiffReviewBatchResponse get(UUID projectId, UUID taskId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        DiffReviewBatchEntity batch = latest(projectId, taskId);
        return response(batch, diffs(batch.getId()));
    }

    /**
     * Returns one immutable patch only after project membership and batch ownership checks.
     */
    public DiffReviewPatchResponse patch(UUID projectId, UUID taskId, UUID diffId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        DiffReviewBatchEntity batch = latest(projectId, taskId);
        DiffEntity diff = diffs.selectById(diffId);
        if (diff == null || !batch.getId().equals(diff.getReviewBatchId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DIFF_REVIEW_PATCH_NOT_FOUND",
                    "Final Diff patch does not exist or is not visible");
        }
        return new DiffReviewPatchResponse(diff.getId().toString(), diff.getProjectRepositoryId().toString(),
                snapshots.load(diff.getSnapshotKey()));
    }

    /**
     * A short DB transaction claims delivery; Worker calls deliberately occur after it commits.
     */
    public DiffReviewBatchResponse confirm(UUID projectId, UUID taskId, UUID actor) {
        TaskEntity task = requireTask(projectId, taskId);
        requireOwnerOrAdmin(task, actor);
        requireDiffDeliveryAllowed(task);
        refreshTargetBranchesBeforeDelivery(task);
        WorkspaceWriteLease workspaceLease = acquireWorkspaceWriteLease(task);
        try {
            DiffReviewBatchEntity batch = transactions.execute(status -> claim(task));
            log.info("diff delivery started projectId={} taskId={} reviewBatchId={} operationId={} mode=confirm",
                    projectId, taskId, batch.getId(), batch.getDeliveryOperationId());
            refreshDiffCard(task, batch);
            List<DiffEntity> values = diffs(batch.getId());
            try {
                preflight(task, values);
            } catch (RuntimeException failure) {
                if (isStaleSnapshot(failure)) {
                    failStaleReview(task, batch, values, failure);
                } else {
                    restoreAfterPreflightFailure(batch.getId(), batch.getDeliveryClaimToken());
                }
                throw failure;
            }
            transactions.execute(status -> {
                accept(task, batch.getId(), actor, batch.getDeliveryClaimToken());
                return null;
            });
            task.setStatus("DELIVERING");
            publishTaskStatusCard(task, "DELIVERING", "已确认 Diff，正在提交并推送代码");
            refreshDiffCard(task, requireBatch(batch.getId()));
            for (DiffEntity diff : diffs(batch.getId())) {
                deliver(task, diff, actor, batch.getDeliveryClaimToken(), workspaceLease);
            }
            finish(task, batch.getId(), batch.getDeliveryClaimToken());
            DiffReviewBatchEntity result = requireBatch(batch.getId());
            refreshDiffCard(task, result);
            return response(result, diffs(result.getId()));
        } finally {
            releaseWorkspaceWriteLease(workspaceLease);
        }
    }

    public DiffReviewBatchResponse reject(UUID projectId, UUID taskId, UUID actor, String reason) {
        TaskEntity task = requireTask(projectId, taskId);
        requireOwnerOrAdmin(task, actor);
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DIFF_REJECT_REASON_REQUIRED", "A rejection reason is required");
        }
        DiffReviewBatchEntity batch = transactions.execute(status -> {
            lockWorkspace(task.getWorkspaceId());
            DiffReviewBatchEntity locked = latestForUpdate(projectId, taskId);
            DiffReviewBatchEntity pending = latestPendingForWorkspaceForUpdate(task.getWorkspaceId());
            if (workspaces != null && (pending == null || !pending.getId().equals(locked.getId()))) {
                throw new ApiException(HttpStatus.CONFLICT, "DIFF_REVIEW_SUPERSEDED",
                        "This Diff was superseded by a newer change in the same Workspace");
            }
            if (!"PENDING_CONFIRMATION".equals(locked.getReviewStatus())
                    || !"NOT_STARTED".equals(locked.getDeliveryStatus())
                    || !"USER".equals(locked.getConfirmationSource())) {
                throw new ApiException(HttpStatus.CONFLICT, "DIFF_REVIEW_NOT_DECIDABLE", "Final Diff is not awaiting review");
            }
            TaskEntity lockedTask = tasks.selectByIdForUpdate(taskId);
            if (lockedTask == null || !projectId.equals(lockedTask.getProjectId())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task does not exist or is not visible");
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            locked.setReviewStatus("REJECTED");
            locked.setReviewReason(reason);
            locked.setReviewedBy(actor);
            locked.setReviewedAt(now);
            locked.setUpdatedAt(now);
            batches.updateById(locked);
            for (DiffEntity diff : diffs(locked.getId())) {
                diff.setStatus("REJECTED");
                diff.setReviewedBy(actor);
                diff.setReviewReason(reason);
                diff.setReviewedAt(now);
                diff.setUpdatedAt(now);
                diffs.updateById(diff);
            }
            lockedTask.setStatus("DIFF_REJECTED");
            lockedTask.setUpdatedAt(now);
            tasks.updateById(lockedTask);
            events.publish(projectId, lockedTask.getRequirementGroupId(), "task.updated", lockedTask.getId().toString(),
                    TaskEventPayloads.taskUpdated(lockedTask));
            events.publish(projectId, lockedTask.getRequirementGroupId(), "diff-review.rejected", locked.getId().toString(),
                    Map.of("projectId", projectId, "taskId", taskId, "reviewBatchId", locked.getId()));
            return locked;
        });
        task.setStatus("DIFF_REJECTED");
        publishTaskStatusCard(task, "DIFF_REJECTED", "Diff 已拒绝，保留当前开发现场等待后续处理");
        refreshDiffCard(task, batch);
        return response(batch, diffs(batch.getId()));
    }

    public DiffReviewBatchResponse retryDelivery(UUID projectId, UUID taskId, UUID actor) {
        TaskEntity task = requireTask(projectId, taskId);
        requireOwnerOrAdmin(task, actor);
        requireDiffDeliveryAllowed(task);
        refreshTargetBranchesBeforeDelivery(task);
        WorkspaceWriteLease workspaceLease = acquireWorkspaceWriteLease(task);
        try {
            DiffReviewBatchEntity batch = transactions.execute(status -> claimRetry(projectId, taskId));
            log.info("diff delivery retry started projectId={} taskId={} reviewBatchId={} operationId={}",
                    projectId, taskId, batch.getId(), batch.getDeliveryOperationId());
            refreshDiffCard(task, batch);
            List<DiffEntity> values = diffs(batch.getId());
            List<DiffEntity> uncommitted = values.stream().filter(this::requiresSnapshotPreflight).toList();
            try {
                preflight(task, uncommitted);
            } catch (RuntimeException failure) {
                if (isStaleSnapshot(failure)) {
                    failStaleRetry(task, batch, values, uncommitted, failure);
                }
                throw failure;
            }
            for (DiffEntity diff : values) {
                if (!"PUSHED".equals(diff.getDeliveryStatus()) && !"MR_CREATED".equals(diff.getDeliveryStatus())) {
                    deliver(task, diff, actor, batch.getDeliveryClaimToken(), workspaceLease);
                }
            }
            finish(task, batch.getId(), batch.getDeliveryClaimToken());
            DiffReviewBatchEntity result = requireBatch(batch.getId());
            refreshDiffCard(task, result);
            return response(result, diffs(result.getId()));
        } finally {
            releaseWorkspaceWriteLease(workspaceLease);
        }
    }

    /**
     * MR_FIRST 系统授权批次的交付入口：校验批次归属与租约后，按既有单仓库交付链路
     * （commit → push → 状态回写）逐仓库执行并收尾。幂等由租约保证——
     * 领域事件与兜底扫描双通道并发到达时，只有持有有效 claimToken 的一方能推进；
     * 已交付（DELIVERED/PARTIALLY_DELIVERED/FAILED 终态）或租约易主时静默跳过。
     * actor 使用任务发起人（系统代执行），交付失败回写 FAILED 状态与稳定失败码，
     * 用户可通过 retry-delivery 重试失败仓库。
     */
    public void deliverSystemAcceptedBatch(UUID projectId, UUID taskId, UUID reviewBatchId, String claimToken) {
        TaskEntity task = requireTask(projectId, taskId);
        requireDiffDeliveryAllowed(task);
        // 本方法会执行 Worker/GitHub 外部调用，不能持有 FOR UPDATE 锁；后续每次状态写入均会
        // 在短事务内用 claimToken 再次校验，租约失效或易主时安全退出。
        DiffReviewBatchEntity batch = batches.selectById(reviewBatchId);
        if (batch == null || !projectId.equals(batch.getProjectId()) || !taskId.equals(batch.getTaskId())) {
            log.warn("mr-first delivery skipped, batch context mismatch, projectId={} taskId={} reviewBatchId={}",
                    projectId, taskId, reviewBatchId);
            return;
        }
        if (!"SYSTEM".equals(batch.getConfirmationSource()) || !"DELIVERING".equals(batch.getDeliveryStatus())
                || !java.util.Objects.equals(batch.getDeliveryClaimToken(), claimToken)) {
            log.info("mr-first delivery skipped, batch not claimable, projectId={} taskId={} reviewBatchId={} status={}",
                    projectId, taskId, reviewBatchId, batch.getDeliveryStatus());
            return;
        }
        log.info("mr-first delivery started projectId={} taskId={} reviewBatchId={} operationId={}",
                projectId, taskId, batch.getId(), batch.getDeliveryOperationId());
        WorkspaceWriteLease workspaceLease = acquireWorkspaceWriteLease(task);
        try {
        List<DiffEntity> values = diffs(batch.getId());
        // MR_FIRST 由系统授权交付，但仍只能提交创建批次时固化的最终 Diff。这里不能省略：
        // batch 入库到异步执行之间，任何 Workspace 漂移都必须阻断 commit/push，而不是把未审查
        // 的后续修改带到远端分支。
        try {
            preflight(task, values.stream().filter(this::requiresSnapshotPreflight).toList());
        } catch (RuntimeException failure) {
            if (isStaleSnapshot(failure)) {
                failStaleReview(task, batch, values, failure);
                return;
            }
            // Worker 瞬时不可用等非快照错误保留租约，由恢复调度器重试，不能错误标记为交付失败。
            throw failure;
        }
        for (DiffEntity diff : values) {
            if (!"PUSHED".equals(diff.getDeliveryStatus()) && !"MR_CREATED".equals(diff.getDeliveryStatus())) {
                deliver(task, diff, task.getCreatedBy(), claimToken, workspaceLease);
            }
        }
        finish(task, batch.getId(), claimToken);
        } finally {
            releaseWorkspaceWriteLease(workspaceLease);
        }
    }

    /**
     * MR_FIRST 的首次异步交付遇到 Worker 或写租约等可重试异常时，不能让旧租约把任务卡住
     * 30 分钟。仅当前 claim 持有者可将到期时间收敛到现在；恢复扫描会在下一轮用新 token
     * 重新领取。快照失效等业务终态由交付链路自行落库，不会走这里。
     */
    public void relinquishSystemDeliveryClaim(UUID projectId, UUID taskId, UUID reviewBatchId, String claimToken) {
        if (claimToken == null || claimToken.isBlank()) return;
        transactions.execute(status -> {
            DiffReviewBatchEntity batch = batches.selectByIdForUpdate(reviewBatchId);
            if (batch == null || !projectId.equals(batch.getProjectId()) || !taskId.equals(batch.getTaskId())
                    || !"SYSTEM".equals(batch.getConfirmationSource())
                    || !"DELIVERING".equals(batch.getDeliveryStatus())
                    || !claimToken.equals(batch.getDeliveryClaimToken())) {
                return null;
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            batch.setDeliveryLeaseExpiresAt(now);
            batch.setUpdatedAt(now);
            batches.updateById(batch);
            return null;
        });
    }

    private DiffReviewBatchEntity claim(TaskEntity task) {
        // Lock order is Workspace -> review batch, matching continuation creation.
        // This prevents a confirm/continuation deadlock under concurrent requests.
        lockWorkspace(task.getWorkspaceId());
        DiffReviewBatchEntity pending = latestPendingForWorkspaceForUpdate(task.getWorkspaceId());
        DiffReviewBatchEntity batch = latestForUpdate(task.getProjectId(), task.getId());
        if (workspaces != null) ensureLatestPendingForWorkspace(batch, pending);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        boolean recoverable = "DELIVERING".equals(batch.getDeliveryStatus())
                && batch.getDeliveryLeaseExpiresAt() != null
                && !batch.getDeliveryLeaseExpiresAt().isAfter(now);
        if (!"PENDING_CONFIRMATION".equals(batch.getReviewStatus())
                || !("NOT_STARTED".equals(batch.getDeliveryStatus()) || recoverable)
                || !"USER".equals(batch.getConfirmationSource())) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_REVIEW_NOT_DECIDABLE", "Final Diff is not awaiting confirmation");
        }
        if (batch.getDeliveryOperationId() == null) batch.setDeliveryOperationId(UUID.randomUUID().toString());
        batch.setDeliveryClaimToken(UUID.randomUUID().toString());
        batch.setDeliveryStatus("DELIVERING");
        batch.setDeliveryLeaseExpiresAt(now.plus(DELIVERY_LEASE));
        batch.setUpdatedAt(now);
        batches.updateById(batch);
        return batch;
    }

    /**
     * A stale Task page may still call confirm after a continuation produced a
     * newer review in the same Workspace.  The task-level lookup alone cannot
     * detect that case, so confirmation is additionally gated by the
     * Workspace-level pending batch.
     */
    private void ensureLatestPendingForWorkspace(DiffReviewBatchEntity batch, DiffReviewBatchEntity pending) {
        if (pending == null || !pending.getId().equals(batch.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_REVIEW_SUPERSEDED",
                    "This Diff was superseded by a newer change in the same Workspace");
        }
    }

    private DiffReviewBatchEntity latestPendingForWorkspaceForUpdate(UUID workspaceId) {
        List<DiffReviewBatchEntity> pending = batches.selectPendingByWorkspaceForUpdate(workspaceId);
        return pending == null || pending.isEmpty() ? null : pending.get(0);
    }

    private void lockWorkspace(UUID workspaceId) {
        if (workspaces != null && workspaceId != null) {
            workspaces.selectByIdForUpdate(workspaceId);
        }
    }

    private DiffReviewBatchEntity claimRetry(UUID projectId, UUID taskId) {
        DiffReviewBatchEntity batch = latestForUpdate(projectId, taskId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        boolean activeDelivery = "DELIVERING".equals(batch.getDeliveryStatus())
                && batch.getDeliveryLeaseExpiresAt() != null
                && batch.getDeliveryLeaseExpiresAt().isAfter(now);
        if (!"ACCEPTED".equals(batch.getReviewStatus()) || "DELIVERED".equals(batch.getDeliveryStatus())
                || activeDelivery) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_DELIVERY_NOT_RETRYABLE",
                    "Final Diff delivery is not retryable");
        }
        if (batch.getDeliveryOperationId() == null) batch.setDeliveryOperationId(UUID.randomUUID().toString());
        batch.setDeliveryClaimToken(UUID.randomUUID().toString());
        batch.setDeliveryStatus("DELIVERING");
        batch.setDeliveryLeaseExpiresAt(now.plus(DELIVERY_LEASE));
        batch.setUpdatedAt(now);
        batches.updateById(batch);
        return batch;
    }

    private void preflight(TaskEntity task, List<DiffEntity> values) {
        for (DiffEntity diff : values) {
            WorkerGitDiff current = worker.createWorkspaceGitDiff(task.getWorkspaceId(), diff.getProjectRepositoryId());
            if (current == null || !diff.getHeadCommit().equalsIgnoreCase(current.getHeadCommit())
                    || !diff.getWorkingTreeHash().equals(current.getDiffHash())) {
                throw new ApiException(HttpStatus.CONFLICT, "DIFF_SNAPSHOT_STALE",
                        "Workspace differs from the reviewed final Diff");
            }
        }
    }

    /**
     * 交付确认前刷新 GitHub/Worker 中的目标分支引用。
     *
     * <p>目标分支推进不会改写 Workspace 的不可变 baseCommit，也不会自动 rebase 旧 Diff；
     * 它只保证后续交付和 MR 预检看到的是远端最新目标分支。调用发生在领取交付租约前，
     * 因此同步失败不会把批次先置成 DELIVERING。</p>
     */
    private void refreshTargetBranchesBeforeDelivery(TaskEntity task) {
        if (gitStores == null || workspaceRepositories == null || task.getWorkspaceId() == null) {
            return;
        }
        List<WorkspaceRepositoryEntity> worktrees = workspaceRepositories.selectByWorkspace(task.getWorkspaceId());
        if (worktrees == null || worktrees.isEmpty()) {
            return;
        }
        Map<UUID, WorkspaceRepositoryEntity> worktreeByRepository = worktrees.stream()
                .filter(Objects::nonNull)
                .filter(value -> value.getProjectRepositoryId() != null)
                .collect(java.util.stream.Collectors.toMap(
                        WorkspaceRepositoryEntity::getProjectRepositoryId,
                        value -> value,
                        (left, right) -> left));
        List<DiffEntity> values = diffs(latest(task.getProjectId(), task.getId()).getId());
        for (DiffEntity diff : values) {
            if (diff.getProjectRepositoryId() == null) {
                continue;
            }
            WorkspaceRepositoryEntity worktree = worktreeByRepository.get(diff.getProjectRepositoryId());
            String targetBranch = targetBranch(worktree);
            if (targetBranch == null) {
                continue;
            }
            ProjectRepositoryEntity repository = repositories.selectById(diff.getProjectRepositoryId());
            if (repository == null || !task.getProjectId().equals(repository.getProjectId())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "交付目标仓库不存在或不可见");
            }
            String targetCommit = gitStores.refreshTargetBranch(task.getProjectId(), repository, targetBranch);
            log.info("delivery target branch refreshed projectId={} taskId={} repositoryId={} targetBranch={} targetCommit={}",
                    task.getProjectId(), task.getId(), diff.getProjectRepositoryId(), targetBranch, targetCommit);
        }
    }

    private String targetBranch(WorkspaceRepositoryEntity worktree) {
        if (worktree == null) {
            return null;
        }
        if (worktree.getBaseRef() != null && !worktree.getBaseRef().isBlank()) {
            return worktree.getBaseRef().trim();
        }
        String legacy = worktree.getBaseCommit();
        if (legacy != null && !legacy.isBlank() && !legacy.matches("(?i)[0-9a-f]{40,64}")) {
            return legacy.trim();
        }
        return null;
    }

    private void accept(TaskEntity task, UUID batchId, UUID actor, String claimToken) {
        DiffReviewBatchEntity batch = batches.selectByIdForUpdate(batchId);
        requireBatchClaim(batch, claimToken);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        batch.setReviewStatus("ACCEPTED");
        // 授权来源固定为 USER：confirm 是用户显式决策，服务端不接收客户端传入的来源字段
        batch.setConfirmationSource("USER");
        batch.setReviewedBy(actor);
        batch.setReviewedAt(now);
        batch.setUpdatedAt(now);
        batches.updateById(batch);
        for (DiffEntity diff : diffs(batchId)) {
            diff.setStatus("ACCEPTED");
            diff.setReviewedBy(actor);
            diff.setReviewedAt(now);
            diff.setUpdatedAt(now);
            diffs.updateById(diff);
        }
        task.setStatus("DELIVERING");
        task.setUpdatedAt(now);
        tasks.updateById(task);
        events.publish(task.getProjectId(), task.getRequirementGroupId(), "task.updated", task.getId().toString(),
                TaskEventPayloads.taskUpdated(task));
        events.publish(task.getProjectId(), task.getRequirementGroupId(), "diff-review.confirmed", batchId.toString(),
                Map.of("projectId", task.getProjectId(), "taskId", task.getId(), "reviewBatchId", batchId));
    }

    private void deliver(TaskEntity task, DiffEntity diff, UUID actor, String claimToken,
                         WorkspaceWriteLease workspaceLease) {
        UUID diffId = diff.getId();
        UUID batchId = diff.getReviewBatchId();
        log.info("repository delivery started projectId={} taskId={} reviewBatchId={} diffId={} repositoryId={} status={}",
                task.getProjectId(), task.getId(), batchId, diffId, diff.getProjectRepositoryId(),
                diff.getDeliveryStatus());
        boolean committed = "COMMITTED".equals(diff.getDeliveryStatus());
        try {
            renewWorkspaceWriteLease(workspaceLease);
            renewBatchLease(diff.getReviewBatchId(), claimToken);
            requireDiffDeliveryAllowed(task);
            if (!committed && !"PUSHED".equals(diff.getDeliveryStatus())
                    && !"MR_CREATED".equals(diff.getDeliveryStatus())) {
                String commitSha = deliveryService.commitVerified(task, diff);
                deliveryService.recordCommitted(task, diffId, commitSha, diff.getReviewBatchId(), claimToken);
                diff = diffs.selectById(diffId);
                committed = true;
            }
            if (!"PUSHED".equals(diff.getDeliveryStatus()) && !"MR_CREATED".equals(diff.getDeliveryStatus())) {
                requireDiffDeliveryAllowed(task);
                mergeRequests.pushAcceptedBranch(task.getProjectId(), task.getId(), diff.getProjectRepositoryId());
                transactions.execute(status -> {
                    markPushed(task, diffId, batchId, claimToken);
                    return null;
                });
                log.info("repository delivery completed projectId={} taskId={} reviewBatchId={} diffId={} repositoryId={} status=PUSHED",
                        task.getProjectId(), task.getId(), batchId, diffId, diff.getProjectRepositoryId());
            }
        } catch (RuntimeException failure) {
            log.error("repository delivery failed projectId={} taskId={} reviewBatchId={} diffId={} repositoryId={} code={} message={}",
                    task.getProjectId(), task.getId(), batchId, diffId, diff.getProjectRepositoryId(),
                    failureCode(failure), failure.getMessage(), failure);
            markDiffFailure(task, diffId, batchId, claimToken, committed, failure);
        }
    }


    private void markPushed(TaskEntity task, UUID diffId, UUID batchId, String claimToken) {
        requireBatchClaim(batches.selectByIdForUpdate(batchId), claimToken);
        DiffEntity current = diffs.selectByIdForUpdate(diffId);
        if (current == null || !batchId.equals(current.getReviewBatchId())
                || !task.getId().equals(current.getTaskId()) || !task.getWorkspaceId().equals(current.getWorkspaceId())) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_BATCH_CONTEXT_INVALID",
                    "Repository Diff no longer belongs to the claimed review batch");
        }
        diffs.markPushed(diffId, LocalDateTime.now(ZoneOffset.UTC));
        events.publish(task.getProjectId(), task.getRequirementGroupId(), "delivery.repository.updated", diffId.toString(),
                Map.of("projectId", task.getProjectId(), "taskId", task.getId(), "diffId", diffId,
                        "repositoryId", current.getProjectRepositoryId(), "deliveryStatus", "PUSHED"));
    }

    private void markDiffFailure(TaskEntity task, UUID diffId, UUID batchId, String claimToken, boolean committed,
                                 RuntimeException failure) {
        transactions.execute(status -> {
            DiffReviewBatchEntity batch = batches.selectByIdForUpdate(batchId);
            if (!ownsBatchClaim(batch, claimToken)) return null;
            DiffEntity current = diffs.selectByIdForUpdate(diffId);
            if (current == null || isPushed(current)) {
                return null;
            }
            // Commit 与 Push 是独立事实。Push 失败后保留 COMMITTED，重试只能继续 push，
            // 不能在已经前进的工作树上再次把旧快照 commit 一遍。
            current.setDeliveryStatus(committed ? "COMMITTED" : "FAILED");
            current.setDeliveryFailureCode(failureCode(failure));
            current.setDeliveryFailureReason("Repository delivery failed (" + failureCode(failure) + ")");
            current.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            diffs.updateById(current);
            log.warn("repository delivery failure persisted projectId={} taskId={} reviewBatchId={} diffId={} repositoryId={} code={}",
                    task.getProjectId(), task.getId(), batchId, diffId, current.getProjectRepositoryId(),
                    failureCode(failure));
            return null;
        });
    }

    private void finish(TaskEntity task, UUID batchId, String claimToken) {
        FinishResult result = transactions.execute(status -> {
            DiffReviewBatchEntity batch = batches.selectByIdForUpdate(batchId);
            requireBatchClaim(batch, claimToken);
            List<DiffEntity> values = diffs(batchId);
            boolean allDelivered = values.stream().allMatch(this::isPushed);
            boolean anyDelivered = values.stream().anyMatch(this::isPushed);
            String previousTaskStatus = task.getStatus();
            String nextTaskStatus = allDelivered
                    ? ("MR_FIRST".equals(task.getDeliveryMode()) ? "WAITING_PREFLIGHT" : "SUCCEEDED")
                    : "DELIVERY_FAILED";
            batch.setDeliveryStatus(allDelivered ? "DELIVERED" : anyDelivered ? "PARTIALLY_DELIVERED" : "FAILED");
            batch.setDeliveryClaimToken(null);
            batch.setDeliveryLeaseExpiresAt(null);
            batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            batches.updateById(batch);
            task.setStatus(nextTaskStatus);
            task.setUpdatedAt(batch.getUpdatedAt());
            tasks.updateById(task);
            boolean statusChanged = !nextTaskStatus.equals(previousTaskStatus);
            log.warn("diff delivery finished projectId={} taskId={} reviewBatchId={} status={} allDelivered={} anyDelivered={}",
                    task.getProjectId(), task.getId(), batchId, batch.getDeliveryStatus(), allDelivered, anyDelivered);
            events.publish(task.getProjectId(), task.getRequirementGroupId(), allDelivered ? "delivery.completed" : "delivery.failed",
                    batchId.toString(), Map.of("projectId", task.getProjectId(), "taskId", task.getId(),
                            "reviewBatchId", batchId, "deliveryStatus", batch.getDeliveryStatus()));
            if (!nextTaskStatus.equals(previousTaskStatus)) {
                events.publish(task.getProjectId(), task.getRequirementGroupId(), "task.updated", task.getId().toString(),
                        TaskEventPayloads.taskUpdated(task));
            }
            if (statusChanged && "WAITING_PREFLIGHT".equals(nextTaskStatus)) {
                // 只发布内部预检意图；Dry Run 的目标分支、Testset 和提交 SHA 由异步服务重新读取。
                domainEvents.publishEvent(new MrFirstPreflightRequestedDomainEvent(task.getProjectId(), task.getId()));
                events.publish(task.getProjectId(), task.getRequirementGroupId(), "mr-first.preflight.requested",
                        task.getId().toString(), Map.of("projectId", task.getProjectId(), "taskId", task.getId(),
                                "deliveryMode", "MR_FIRST"));
            }
            return new FinishResult(allDelivered, "SUCCEEDED".equals(nextTaskStatus), statusChanged,
                    statusChanged && "WAITING_PREFLIGHT".equals(nextTaskStatus));
        });
        if (result != null && result.enteredPreflight()) {
            publishPreflightReadyCard(task);
        }
        if (result != null) {
            if (!result.enteredPreflight()) {
                publishTaskStatusCard(task, task.getStatus(), taskStatusMessage(task.getStatus()));
            }
            refreshLatestDiffCard(task, batchId);
        }
        if (result != null && result.statusChanged() && notificationService != null
                && (result.taskCompleted() || !result.allDelivered())) {
            String kind = result.taskCompleted() ? "TASK_COMPLETED" : "TASK_FAILED";
            notificationService.notify(task.getCreatedBy(), task.getProjectId(), task.getRequirementGroupId(), kind,
                    (result.taskCompleted() ? "任务完成：" : "任务交付失败：") + task.getTitle(),
                    result.taskCompleted() ? task.getRequirement() : "Diff 交付未全部完成，请查看交付失败详情",
                    task.getId().toString());
        }
    }

    /**
     * 已有真实 Commit 的仓库不能再按旧工作树 Diff 预检：Commit 后工作树通常已干净，旧
     * {@code workingTreeHash} 自然不会相等。它们在重试时仅允许继续 Push，后续由
     * {@link MergeRequestService#pushAcceptedBranch(UUID, UUID, UUID)} 校验当前 Workspace HEAD 与
     * 已接受 Diff 的 Commit 一致。
     */
    private boolean requiresSnapshotPreflight(DiffEntity diff) {
        return !"COMMITTED".equals(diff.getDeliveryStatus())
                && !"PUSHED".equals(diff.getDeliveryStatus())
                && !"MR_CREATED".equals(diff.getDeliveryStatus());
    }

    private WorkspaceWriteLease acquireWorkspaceWriteLease(TaskEntity task) {
        return workspaceWriteLeases == null ? null
                : workspaceWriteLeases.acquire(task.getProjectId(), task.getWorkspaceId(), task.getId());
    }

    private void requireDiffDeliveryAllowed(TaskEntity task) {
        if (developmentGuard != null) {
            developmentGuard.requireDiffDeliveryAllowed(task.getProjectId(), task.getWorkspaceId());
        }
    }

    private void renewWorkspaceWriteLease(WorkspaceWriteLease lease) {
        if (lease != null) {
            workspaceWriteLeases.renew(lease);
        }
    }

    private void releaseWorkspaceWriteLease(WorkspaceWriteLease lease) {
        if (lease != null) {
            workspaceWriteLeases.release(lease);
        }
    }

    /**
     * MR_FIRST 的代码已经真实推送，但尚未创建 MR。该卡只在状态首次进入
     * WAITING_PREFLIGHT 后发送，避免重试/恢复流程反复刷群；Dry Run、CQ+1 和创建 MR
     * 仍必须由各自真实接口推进，不能由卡片或 SSE 伪造完成。
     */
    private void publishPreflightReadyCard(TaskEntity task) {
        if (messageService == null || task == null || task.getId() == null || task.getRequirementGroupId() == null) {
            return;
        }
        Map<String, Object> content = new HashMap<>();
        content.put("taskId", task.getId().toString());
        content.put("status", "WAITING_PREFLIGHT");
        content.put("deliveryMode", "MR_FIRST");
        content.put("message", "代码已推送，请完成 Dry Run 和独立成员 CQ+1 后创建 MR");
        addRepositoryContext(content, task, currentBatchRepositoryIds(task));
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
            log.warn("preflight ready card skipped taskId={}: {}", task.getId(), failure.getMessage());
        }
    }

    private void publishTaskStatusCard(TaskEntity task, String status, String message) {
        if (messageService == null || task == null || task.getRequirementGroupId() == null) return;
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("taskId", task.getId().toString());
        content.put("status", status);
        content.put("phase", "DELIVERY");
        content.put("message", message);
        addRepositoryContext(content, task, currentBatchRepositoryIds(task));
        MessageSendRequest body = new MessageSendRequest();
        body.setType("TASK_STATUS");
        body.setClientMessageId("task-card-" + task.getId());
        body.setContent(content);
        try {
            UUID senderId = orchestratorAgents == null ? null : orchestratorAgents.resolveIdForTask(task);
            messageService.upsertTaskStatusCard(task.getRequirementGroupId(), senderId, body);
        } catch (RuntimeException failure) {
            log.warn("task status card refresh skipped taskId={}: {}", task.getId(), failure.getMessage());
        }
    }

    private String taskStatusMessage(String status) {
        return switch (status == null ? "" : status) {
            case "SUCCEEDED" -> "Diff 已交付完成，任务已完成";
            case "DELIVERY_FAILED" -> "Diff 交付失败，请检查失败仓库后重试";
            case "WAITING_PREFLIGHT" -> "代码已推送，等待 MR 前预检";
            default -> "任务交付状态更新：" + status;
        };
    }

    private void addRepositoryContext(Map<String, Object> content, TaskEntity task, List<UUID> repositoryIds) {
        if (repositoryContextService == null || content == null || task == null) return;
        try {
            content.put("repositoryMappings", repositoryContextService.allRepositories(task));
            content.put("currentRepositoryPaths", repositoryIds == null
                    ? List.of() : repositoryContextService.pathsForRepositories(task, repositoryIds));
        } catch (RuntimeException failure) {
            log.warn("repository context omitted from delivery card taskId={}: {}", task.getId(), failure.getMessage());
            content.put("repositoryMappings", List.of());
            content.put("currentRepositoryPaths", List.of());
        }
    }

    private List<UUID> currentBatchRepositoryIds(TaskEntity task) {
        try {
            DiffReviewBatchEntity batch = latest(task.getProjectId(), task.getId());
            if (batch == null) return List.of();
            return diffs(batch.getId()).stream().map(DiffEntity::getProjectRepositoryId)
                    .filter(java.util.Objects::nonNull).distinct().toList();
        } catch (RuntimeException failure) {
            log.warn("current diff repositories unavailable taskId={}: {}", task.getId(), failure.getMessage());
            return List.of();
        }
    }

    private void refreshDiffCard(TaskEntity task, DiffReviewBatchEntity batch) {
        if (messageService == null || task == null || batch == null || task.getRequirementGroupId() == null) {
            return;
        }
        List<DiffEntity> values = diffs(batch.getId());
        if (values.isEmpty()) return;
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("taskId", task.getId().toString());
        content.put("diffId", values.get(0).getId().toString());
        content.put("reviewBatchId", batch.getId().toString());
        content.put("title", task.getTitle());
        content.put("additions", values.stream().map(DiffEntity::getChangeStats).filter(java.util.Objects::nonNull)
                .mapToInt(stats -> stats.get("additions") instanceof Number n ? n.intValue() : 0).sum());
        content.put("deletions", values.stream().map(DiffEntity::getChangeStats).filter(java.util.Objects::nonNull)
                .mapToInt(stats -> stats.get("deletions") instanceof Number n ? n.intValue() : 0).sum());
        content.put("reviewStatus", batch.getReviewStatus());
        content.put("reviewReason", batch.getReviewReason());
        content.put("deliveryStatus", diffCardDeliveryStatus(batch, values));
        MessageSendRequest body = new MessageSendRequest();
        body.setType("DIFF");
        body.setClientMessageId("diff-card-" + task.getId());
        body.setContent(content);
        try {
            UUID senderId = orchestratorAgents == null ? null : orchestratorAgents.resolveIdForTask(task);
            if (senderId != null) {
                messageService.upsertDiffCard(task.getRequirementGroupId(), senderId, body);
            } else {
                messageService.upsertDiffCard(task.getRequirementGroupId(), null, body);
            }
        } catch (RuntimeException failure) {
            log.warn("diff card refresh skipped taskId={} batchId={}: {}", task.getId(), batch.getId(),
                    failure.getMessage());
        }
    }

    private void refreshLatestDiffCard(TaskEntity task, UUID batchId) {
        DiffReviewBatchEntity batch = batches.selectById(batchId);
        if (batch != null) refreshDiffCard(task, batch);
    }

    private String diffCardDeliveryStatus(DiffReviewBatchEntity batch, List<DiffEntity> values) {
        if ("FAILED".equals(batch.getDeliveryStatus()) || "PARTIALLY_DELIVERED".equals(batch.getDeliveryStatus())) {
            return "DELIVERY_FAILED";
        }
        if (values.stream().anyMatch(diff -> "MR_CREATED".equals(diff.getDeliveryStatus()))) {
            return "MR_CREATED";
        }
        if (values.stream().anyMatch(diff -> "PUSHED".equals(diff.getDeliveryStatus()))) {
            return "PUSHED";
        }
        if (values.stream().anyMatch(diff -> "COMMITTED".equals(diff.getDeliveryStatus()))) {
            return "COMMITTED";
        }
        return "NOT_STARTED";
    }

    private boolean isStaleSnapshot(RuntimeException failure) {
        return failure instanceof ApiException api && "DIFF_SNAPSHOT_STALE".equals(api.code());
    }

    /** 快照已失效时终止本次交付，避免任务无限停留在待确认状态。 */
    private void failStaleReview(TaskEntity task, DiffReviewBatchEntity batch, List<DiffEntity> values,
            RuntimeException failure) {
        transactions.execute(status -> {
            DiffReviewBatchEntity currentBatch = batches.selectByIdForUpdate(batch.getId());
            if (!ownsBatchClaim(currentBatch, batch.getDeliveryClaimToken())) {
                return null;
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            currentBatch.setDeliveryStatus("FAILED");
            currentBatch.setDeliveryClaimToken(null);
            currentBatch.setDeliveryLeaseExpiresAt(null);
            currentBatch.setUpdatedAt(now);
            batches.updateById(currentBatch);
            for (DiffEntity diff : values) {
                diff.setDeliveryStatus("FAILED");
                diff.setDeliveryFailureCode("DIFF_SNAPSHOT_STALE");
                diff.setDeliveryFailureReason(failure.getMessage());
                diff.setUpdatedAt(now);
                diffs.updateById(diff);
            }
            task.setStatus("DELIVERY_FAILED");
            task.setFailureCode("DIFF_SNAPSHOT_STALE");
            task.setFailureReason("交付快照已失效，请重新生成 Diff 后重试");
            task.setFailureRetryable(true);
            task.setFailureOccurredAt(now);
            task.setUpdatedAt(now);
            tasks.updateById(task);
            events.publish(task.getProjectId(), task.getRequirementGroupId(), "task.updated", task.getId().toString(),
                    TaskEventPayloads.taskUpdated(task));
            events.publish(task.getProjectId(), task.getRequirementGroupId(), "task.diff-review.failed",
                    currentBatch.getId().toString(),
                    Map.of("projectId", task.getProjectId(), "taskId", task.getId(),
                            "reviewBatchId", currentBatch.getId(), "reason", "DIFF_SNAPSHOT_STALE"));
            return null;
        });
        refreshLatestDiffCard(task, batch.getId());
    }

    /**
     * 交付重试只会让尚未 Commit 的快照失效。已 Push 的仓库必须保持其真实事实，不能因为
     * 另一仓库的 Diff 漂移被回写成 FAILED；否则会制造错误的重复 Push/MR 重试入口。
     */
    private void failStaleRetry(TaskEntity task, DiffReviewBatchEntity batch, List<DiffEntity> values,
                                List<DiffEntity> staleValues, RuntimeException failure) {
        transactions.execute(status -> {
            DiffReviewBatchEntity currentBatch = batches.selectByIdForUpdate(batch.getId());
            if (!ownsBatchClaim(currentBatch, batch.getDeliveryClaimToken())) {
                return null;
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            boolean anyPushed = values.stream().anyMatch(this::isPushed);
            currentBatch.setDeliveryStatus(anyPushed ? "PARTIALLY_DELIVERED" : "FAILED");
            currentBatch.setDeliveryClaimToken(null);
            currentBatch.setDeliveryLeaseExpiresAt(null);
            currentBatch.setUpdatedAt(now);
            batches.updateById(currentBatch);
            for (DiffEntity staleValue : staleValues) {
                DiffEntity current = diffs.selectByIdForUpdate(staleValue.getId());
                if (current == null || isPushed(current)) {
                    continue;
                }
                current.setDeliveryStatus("FAILED");
                current.setDeliveryFailureCode("DIFF_SNAPSHOT_STALE");
                current.setDeliveryFailureReason(failure.getMessage());
                current.setUpdatedAt(now);
                diffs.updateById(current);
            }
            task.setStatus("DELIVERY_FAILED");
            task.setFailureCode("DIFF_SNAPSHOT_STALE");
            task.setFailureReason("交付快照已失效，请重新生成 Diff 后重试");
            task.setFailureRetryable(true);
            task.setFailureOccurredAt(now);
            task.setUpdatedAt(now);
            tasks.updateById(task);
            events.publish(task.getProjectId(), task.getRequirementGroupId(), "task.updated", task.getId().toString(),
                    TaskEventPayloads.taskUpdated(task));
            events.publish(task.getProjectId(), task.getRequirementGroupId(), "task.diff-review.failed",
                    currentBatch.getId().toString(),
                    Map.of("projectId", task.getProjectId(), "taskId", task.getId(),
                            "reviewBatchId", currentBatch.getId(), "reason", "DIFF_SNAPSHOT_STALE"));
            return null;
        });
        refreshLatestDiffCard(task, batch.getId());
    }

    private static final class FinishResult {
        private final boolean allDelivered;
        private final boolean taskCompleted;
        private final boolean statusChanged;
        private final boolean enteredPreflight;

        private FinishResult(boolean allDelivered, boolean taskCompleted, boolean statusChanged,
                             boolean enteredPreflight) {
            this.allDelivered = allDelivered;
            this.taskCompleted = taskCompleted;
            this.statusChanged = statusChanged;
            this.enteredPreflight = enteredPreflight;
        }

        private boolean allDelivered() {
            return allDelivered;
        }

        private boolean taskCompleted() {
            return taskCompleted;
        }

        private boolean statusChanged() {
            return statusChanged;
        }

        private boolean enteredPreflight() {
            return enteredPreflight;
        }
    }

    private boolean isPushed(DiffEntity diff) {
        return "PUSHED".equals(diff.getDeliveryStatus()) || "MR_CREATED".equals(diff.getDeliveryStatus());
    }

    private void restoreAfterPreflightFailure(UUID batchId, String claimToken) {
        transactions.execute(status -> {
            DiffReviewBatchEntity batch = batches.selectByIdForUpdate(batchId);
            if (!ownsBatchClaim(batch, claimToken) || !"PENDING_CONFIRMATION".equals(batch.getReviewStatus())) {
                return null;
            }
            batch.setDeliveryStatus("NOT_STARTED");
            batch.setDeliveryClaimToken(null);
            batch.setDeliveryLeaseExpiresAt(null);
            batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            batches.updateById(batch);
            return null;
        });
    }

    private void renewBatchLease(UUID batchId, String claimToken) {
        if (batchId == null) return;
        transactions.executeWithoutResult(status -> {
            DiffReviewBatchEntity batch = batches.selectByIdForUpdate(batchId);
            requireBatchClaim(batch, claimToken);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            batch.setDeliveryLeaseExpiresAt(now.plus(DELIVERY_LEASE));
            batch.setUpdatedAt(now);
            batches.updateById(batch);
        });
    }

    private void requireBatchClaim(DiffReviewBatchEntity batch, String claimToken) {
        if (!ownsBatchClaim(batch, claimToken)) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_DELIVERY_CLAIM_LOST",
                    "Final Diff delivery claim is no longer active");
        }
    }

    private boolean ownsBatchClaim(DiffReviewBatchEntity batch, String claimToken) {
        return batch != null && claimToken != null && "DELIVERING".equals(batch.getDeliveryStatus())
                && claimToken.equals(batch.getDeliveryClaimToken());
    }

    private String failureCode(RuntimeException failure) {
        return failure instanceof ApiException api ? api.code() : "REPOSITORY_DELIVERY_FAILED";
    }

    private DiffReviewBatchEntity latest(UUID projectId, UUID taskId) {
        DiffReviewBatchEntity batch = batches.selectOne(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getProjectId, projectId).eq(DiffReviewBatchEntity::getTaskId, taskId)
                .orderByDesc(DiffReviewBatchEntity::getCreatedAt).last("LIMIT 1"));
        if (batch == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "DIFF_REVIEW_NOT_FOUND", "Final Diff review does not exist");
        return batch;
    }

    private DiffReviewBatchEntity latestForUpdate(UUID projectId, UUID taskId) {
        DiffReviewBatchEntity batch = latest(projectId, taskId);
        DiffReviewBatchEntity locked = batches.selectByIdForUpdate(batch.getId());
        if (locked == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DIFF_REVIEW_NOT_FOUND", "Final Diff review does not exist");
        }
        return locked;
    }

    private DiffReviewBatchEntity requireBatch(UUID id) {
        DiffReviewBatchEntity batch = batches.selectById(id);
        if (batch == null)
            throw new ApiException(HttpStatus.NOT_FOUND, "DIFF_REVIEW_NOT_FOUND", "Final Diff review does not exist");
        return batch;
    }

    private List<DiffEntity> diffs(UUID batchId) {
        return diffs.selectList(Wrappers.<DiffEntity>lambdaQuery().eq(DiffEntity::getReviewBatchId, batchId)
                .orderByAsc(DiffEntity::getProjectRepositoryId));
    }

    private TaskEntity requireTask(UUID projectId, UUID taskId) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task does not exist or is not visible");
        }
        return task;
    }

    private void requireOwnerOrAdmin(TaskEntity task, UUID actor) {
        if (!access.isOwnerOrAdmin(task.getCreatedBy(), task.getProjectId(), actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DIFF_REVIEW_FORBIDDEN", "Only the Task owner or Project Admin may decide the final Diff");
        }
    }

    private DiffReviewBatchResponse response(DiffReviewBatchEntity batch, List<DiffEntity> values) {
        List<DiffListItemResponse> items = values.stream().map(value -> new DiffListItemResponse(value.getId().toString(),
                value.getProjectId().toString(), value.getTaskId().toString(), value.getTaskRunId().toString(),
                value.getTaskStepId() == null ? null : value.getTaskStepId().toString(), null,
                value.getWorkspaceId().toString(), value.getProjectRepositoryId().toString(), value.getBaseCommit(),
                value.getSourceBranch(), value.getHeadCommit(), value.getStatus(), value.getChangeStats(),
                value.getCreatedAt().toInstant(ZoneOffset.UTC).toString())).toList();
        return new DiffReviewBatchResponse(batch.getId().toString(), batch.getTaskId().toString(), batch.getReviewStatus(),
                batch.getConfirmationSource(), batch.getDeliveryStatus(), batch.getAggregateHash(), batch.getReviewReason(),
                items, repositoryDeliveries(batch, values));
    }

    private List<DiffRepositoryDeliveryResponse> repositoryDeliveries(DiffReviewBatchEntity batch,
                                                                      List<DiffEntity> values) {
        Map<UUID, ProjectRepositoryEntity> repositoryById = new HashMap<>();
        if (!values.isEmpty()) {
            repositories.selectBatchIds(values.stream().map(DiffEntity::getProjectRepositoryId).distinct().toList())
                    .forEach(repository -> repositoryById.put(repository.getId(), repository));
        }
        Map<UUID, GitHubRepositoryEntity> githubById = new HashMap<>();
        List<UUID> githubIds = repositoryById.values().stream().map(ProjectRepositoryEntity::getRepositoryId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (!githubIds.isEmpty()) {
            githubRepositories.selectBatchIds(githubIds)
                    .forEach(repository -> githubById.put(repository.getId(), repository));
        }
        List<MergeRequestEntity> matchingMrs = mergeRequestMapper.selectList(Wrappers.<MergeRequestEntity>lambdaQuery()
                .eq(MergeRequestEntity::getWorkspaceId, batch.getWorkspaceId())
                .orderByDesc(MergeRequestEntity::getCreatedAt));
        return values.stream().map(diff -> {
            ProjectRepositoryEntity repository = repositoryById.get(diff.getProjectRepositoryId());
            MergeRequestEntity mr = matchingMrs.stream()
                    .filter(candidate -> diff.getProjectRepositoryId().equals(candidate.getProjectRepositoryId()))
                    .filter(candidate -> diff.getSourceBranch().equals(candidate.getSourceBranch()))
                    .filter(candidate -> diff.getHeadCommit() != null
                            && diff.getHeadCommit().equalsIgnoreCase(candidate.getHeadCommit()))
                    .findFirst().orElse(null);
            DiffMergeRequestSummaryResponse mrSummary = null;
            if ("MR_CREATED".equals(diff.getDeliveryStatus()) && mr != null && mr.getProviderNumber() != null) {
                String webUrl = null;
                if (repository != null) {
                    GitHubRepositoryEntity github = githubById.get(repository.getRepositoryId());
                    if (github != null && github.getOwnerLogin() != null && github.getName() != null) {
                        webUrl = "https://github.com/" + github.getOwnerLogin() + "/" + github.getName()
                                + "/pull/" + mr.getProviderNumber();
                    }
                }
                mrSummary = new DiffMergeRequestSummaryResponse(mr.getId().toString(), mr.getProviderNumber(),
                        mr.getTitle(), mr.getStatus(), webUrl);
            }
            GitHubRepositoryEntity github = repository == null ? null : githubById.get(repository.getRepositoryId());
            String repositoryName = repository == null ? null : repository.getDisplayName();
            if ((repositoryName == null || repositoryName.isBlank()) && github != null)
                repositoryName = github.getName();
            String deliveryStatus = diff.getDeliveryStatus() == null ? "NOT_STARTED" : diff.getDeliveryStatus();
            return new DiffRepositoryDeliveryResponse(diff.getProjectRepositoryId().toString(), repositoryName,
                    diff.getId().toString(), deliveryStatus, diff.getDeliveryFailureCode(),
                    diff.getDeliveryFailureReason(), mrSummary,
                    diff.getUpdatedAt() == null ? null : diff.getUpdatedAt().toInstant(ZoneOffset.UTC).toString());
        }).toList();
    }

}
