package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.PreflightGateResponse;
import qg.qgent.entity.DryRunEntity;
import qg.qgent.entity.PreflightCqReviewEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.DryRunMapper;
import qg.qgent.mapper.PreflightCqReviewMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.service.event.PreflightCqApprovedDomainEvent;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * MR 创建前的预检门禁。
 *
 * 不调用 GitHub 的可合并性字段，也不信任客户端提交的 SHA 或状态；只有固定提交上的真实
 * Dry Run 和独立人工 CQ 审查能让门禁通过。
 */
@Service
public class PreflightGateService {
    private final DryRunMapper dryRuns;
    private final PreflightCqReviewMapper cqReviews;
    private final TaskMapper tasks;
    private final WorkspaceRepositoryMapper worktrees;
    private final ProjectRepositoryMapper repositories;
    private final ProjectAccessService access;
    private final GitStoreSyncService gitStores;
    private final EventService events;
    /** CQ 自动建 MR 的领域事件；与用于浏览器刷新的 SSE 保持独立。 */
    private final ApplicationEventPublisher domainEvents;
    /** 只包裹 CQ 落库和事件发布，绝不在事务中调用 GitHub 或 Worker。 */
    private final TransactionTemplate transactions;

    public PreflightGateService(DryRunMapper dryRuns, PreflightCqReviewMapper cqReviews, TaskMapper tasks,
                                WorkspaceRepositoryMapper worktrees, ProjectRepositoryMapper repositories,
                                ProjectAccessService access, GitStoreSyncService gitStores, EventService events,
                                ApplicationEventPublisher domainEvents, TransactionTemplate transactions) {
        this.dryRuns = dryRuns;
        this.cqReviews = cqReviews;
        this.tasks = tasks;
        this.worktrees = worktrees;
        this.repositories = repositories;
        this.access = access;
        this.gitStores = gitStores;
        this.events = events;
        this.domainEvents = domainEvents;
        this.transactions = transactions;
    }

    public PreflightGateResponse get(UUID projectId, UUID taskId, UUID repositoryId, String targetBranch, UUID actor) {
        access.requireProjectMember(projectId, actor);
        TaskEntity task = requireTask(projectId, taskId);
        WorkspaceRepositoryEntity worktree = requireWorktree(task, repositoryId);
        String branch = normalizeTargetBranch(targetBranch);
        String targetCommit = resolveTargetCommit(projectId, repositoryId, branch);
        return evaluate(task, worktree, repositoryId, branch, targetCommit);
    }

    /**
     * CQ+1 仅允许对已经通过、且仍对应当前 Task 工作树和目标分支基准的 Dry Run 作出。
     */
    public PreflightGateResponse approve(UUID projectId, UUID dryRunId, UUID actor, String reason) {
        return decide(projectId, dryRunId, actor, "APPROVED", reason);
    }

    public PreflightGateResponse reject(UUID projectId, UUID dryRunId, UUID actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PREFLIGHT_CQ_REJECTION_REASON_REQUIRED", "拒绝 CQ 必须给出修改意见");
        }
        return decide(projectId, dryRunId, actor, "REJECTED", reason);
    }

    /**
     * MR 创建侧调用。targetCommit 已在事务外由 resolveTargetCommit 固定，避免持锁时外调 Worker。
     */
    public void requireReady(TaskEntity task, WorkspaceRepositoryEntity worktree, UUID repositoryId,
                             String targetBranch, String targetCommit) {
        requireEvidence(task, worktree, repositoryId, targetBranch, targetCommit);
    }

    /**
     * 返回在给定源/目标提交上已经核验的预检事实。MR 落库阶段调用此方法，确保投影的
     * DRY_RUN 与 CQ+1 都来自同一固定 Git 上下文，而不是客户端或 GitHub 推导出的状态。
     */
    public PreflightEvidence requireEvidence(TaskEntity task, WorkspaceRepositoryEntity worktree, UUID repositoryId,
                                             String targetBranch, String targetCommit) {
        PreflightGateResponse gate = evaluate(task, worktree, repositoryId, targetBranch, targetCommit);
        if (!"PASSED".equals(gate.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_PREFLIGHT_NOT_PASSED",
                    "MR 创建前预检未通过", gate.getBlockers().stream()
                    .map(blocker -> java.util.Map.of("code", blocker)).toList());
        }
        DryRunEntity dryRun = latestDryRun(task.getProjectId(), task.getId(), repositoryId, worktree.getHeadCommit(),
                targetBranch, targetCommit);
        PreflightCqReviewEntity cq = dryRun == null ? null : latestCq(dryRun.getId(), worktree.getHeadCommit(),
                targetCommit, task.getCreatedBy());
        if (dryRun == null || cq == null || !"PASSED".equals(dryRun.getStatus()) || !"APPROVED".equals(cq.getDecision())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_PREFLIGHT_NOT_PASSED", "MR 创建前预检未通过");
        }
        return new PreflightEvidence(dryRun, cq);
    }

    /** 已通过 MR 前预检的不可变 Dry Run 与独立 CQ+1 证据。 */
    public record PreflightEvidence(DryRunEntity dryRun, PreflightCqReviewEntity cqReview) {
    }

    /** 在事务外解析当前目标分支，并校验仓库归属。 */
    public String resolveTargetCommit(UUID projectId, UUID repositoryId, String targetBranch) {
        ProjectRepositoryEntity repository = repositories.selectById(repositoryId);
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "仓库不存在或不可见");
        }
        return gitStores.refreshTargetBranch(projectId, repository, normalizeTargetBranch(targetBranch));
    }

    /**
     * 对外暴露给所有 MR 前预检调用方，避免创建、查询和 Dry Run 使用不同的分支规范化规则。
     */
    public String normalizeTargetBranch(String targetBranch) {
        return gitStores.normalizeTargetBranch(targetBranch);
    }

    private PreflightGateResponse decide(UUID projectId, UUID dryRunId, UUID actor, String decision, String reason) {
        access.requireProjectMember(projectId, actor);
        DryRunEntity dryRun = dryRuns.selectById(dryRunId);
        if (dryRun == null || !projectId.equals(dryRun.getProjectId()) || dryRun.getTaskId() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DRY_RUN_NOT_FOUND", "试运行不存在或不可见");
        }
        if (!"PASSED".equals(dryRun.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "PREFLIGHT_DRY_RUN_NOT_PASSED", "只有通过的 Dry Run 可以进行 CQ 审查");
        }
        TaskEntity task = requireTask(projectId, dryRun.getTaskId());
        if (actor.equals(task.getCreatedBy())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PREFLIGHT_CQ_AUTHOR_FORBIDDEN", "Task 发起人不能为自己的提交给出 CQ+1");
        }
        if (!isPreflightActionable(task)) {
            throw new ApiException(HttpStatus.CONFLICT, "PREFLIGHT_TASK_NOT_READY",
                    "Task 尚未处于可进行 MR 前预检的稳定状态");
        }
        WorkspaceRepositoryEntity worktree = requireWorktree(task, dryRun.getProjectRepositoryId());
        String currentTarget = resolveTargetCommit(projectId, dryRun.getProjectRepositoryId(), dryRun.getTargetBranch());
        if (!dryRun.getHeadCommit().equals(worktree.getHeadCommit())
                || !dryRun.getResolvedTargetCommit().equals(currentTarget)) {
            throw new ApiException(HttpStatus.CONFLICT, "PREFLIGHT_CONTEXT_STALE", "Dry Run 对应的源提交或目标基准已变化，请重新执行");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        PreflightCqReviewEntity review = new PreflightCqReviewEntity();
        review.setId(UuidV7.next());
        review.setProjectId(projectId);
        review.setTaskId(task.getId());
        review.setProjectRepositoryId(dryRun.getProjectRepositoryId());
        review.setDryRunId(dryRun.getId());
        review.setSourceCommit(dryRun.getHeadCommit());
        review.setTargetBranch(dryRun.getTargetBranch());
        review.setTargetCommit(dryRun.getResolvedTargetCommit());
        review.setDecision(decision);
        review.setReviewerUserId(actor);
        review.setReason(normalizeReason(reason));
        review.setReviewedAt(now);
        review.setCreatedAt(now);
        return transactions.execute(status -> {
            cqReviews.insert(review);
            publishUpdated(task, dryRun, decision);
            return evaluate(task, worktree, dryRun.getProjectRepositoryId(), dryRun.getTargetBranch(), currentTarget);
        });
    }

    private PreflightGateResponse evaluate(TaskEntity task, WorkspaceRepositoryEntity worktree, UUID repositoryId,
                                           String targetBranch, String targetCommit) {
        String sourceCommit = worktree.getHeadCommit();
        List<String> blockers = new ArrayList<>();
        if (!isPreflightActionable(task)) blockers.add("TASK_NOT_READY");
        DryRunEntity dryRun = sourceCommit == null ? null : latestDryRun(task.getProjectId(), task.getId(), repositoryId,
                sourceCommit, targetBranch, targetCommit);
        if (dryRun == null) blockers.add("DRY_RUN_MISSING");
        else if (!"PASSED".equals(dryRun.getStatus())) blockers.add("DRY_RUN_" + dryRun.getStatus());

        PreflightCqReviewEntity cq = dryRun == null ? null : latestCq(dryRun.getId(), sourceCommit, targetCommit,
                task.getCreatedBy());
        // 查询条件已排除发起人；再次在应用层防御，避免历史异常数据或非标准 Mapper 实现
        // 将自审记录误当成独立 CQ+1。
        if (cq != null && task.getCreatedBy() != null && task.getCreatedBy().equals(cq.getReviewerUserId())) {
            cq = null;
        }
        if (cq == null) blockers.add("CQ_PLUS_ONE_MISSING");
        else if (!"APPROVED".equals(cq.getDecision())) blockers.add("CQ_PLUS_ONE_" + cq.getDecision());

        String status = blockers.isEmpty() ? "PASSED" : blockers.stream().anyMatch(value -> value.endsWith("FAILED")
                || value.endsWith("REJECTED")) ? "FAILED" : "PENDING";
        return new PreflightGateResponse(id(task.getId()), id(repositoryId), sourceCommit, targetBranch, targetCommit,
                status, List.copyOf(blockers), drySummary(dryRun), cqSummary(cq));
    }

    private DryRunEntity latestDryRun(UUID projectId, UUID taskId, UUID repositoryId, String sourceCommit,
                                      String targetBranch, String targetCommit) {
        return dryRuns.selectOne(Wrappers.<DryRunEntity>lambdaQuery()
                .eq(DryRunEntity::getProjectId, projectId).eq(DryRunEntity::getTaskId, taskId)
                .eq(DryRunEntity::getProjectRepositoryId, repositoryId).eq(DryRunEntity::getHeadCommit, sourceCommit)
                .eq(DryRunEntity::getTargetBranch, targetBranch).eq(DryRunEntity::getResolvedTargetCommit, targetCommit)
                .orderByDesc(DryRunEntity::getCreatedAt).orderByDesc(DryRunEntity::getId).last("LIMIT 1"));
    }

    private PreflightCqReviewEntity latestCq(UUID dryRunId, String sourceCommit, String targetCommit,
                                             UUID taskCreatorId) {
        return cqReviews.selectOne(Wrappers.<PreflightCqReviewEntity>lambdaQuery()
                .eq(PreflightCqReviewEntity::getDryRunId, dryRunId)
                .eq(PreflightCqReviewEntity::getSourceCommit, sourceCommit)
                .eq(PreflightCqReviewEntity::getTargetCommit, targetCommit)
                // 新写入已在 decide 中拦截；这里仍显式排除历史脏数据，不能让任务发起人给自己的
                // 旧审批成为创建 MR 的 CQ+1 事实。
                .ne(taskCreatorId != null, PreflightCqReviewEntity::getReviewerUserId, taskCreatorId)
                .orderByDesc(PreflightCqReviewEntity::getReviewedAt)
                .orderByDesc(PreflightCqReviewEntity::getId).last("LIMIT 1"));
    }

    /**
     * 预检只服务于仍可创建 MR 的代码快照。MR_FIRST 必须已完成全部仓库的 Push；
     * DIFF_FIRST 则必须已完成用户确认后的交付。这样不会让执行中的旧 Dry Run/CQ
     * 在任务后来进入稳定态后被误复用。
     */
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
        if (task.getWorkspaceId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PREFLIGHT_WORKSPACE_MISSING", "Task 尚未准备 Workspace");
        }
        return worktrees.selectByWorkspace(task.getWorkspaceId()).stream()
                .filter(value -> repositoryId.equals(value.getProjectRepositoryId())).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "PREFLIGHT_REPOSITORY_NOT_IN_WORKSPACE",
                        "Task Workspace 不包含目标仓库"));
    }

    private void publishUpdated(TaskEntity task, DryRunEntity dryRun, String decision) {
        if ("APPROVED".equals(decision)) {
            domainEvents.publishEvent(new PreflightCqApprovedDomainEvent(task.getProjectId(), dryRun.getId()));
        }
        events.publish(task.getProjectId(), task.getRequirementGroupId(), "preflight.updated", dryRun.getId().toString(),
                java.util.Map.of("projectId", task.getProjectId(), "taskId", task.getId(),
                        "repositoryId", dryRun.getProjectRepositoryId(), "dryRunId", dryRun.getId(),
                        "targetBranch", dryRun.getTargetBranch(), "decision", decision));
    }

    private PreflightGateResponse.DryRunSummary drySummary(DryRunEntity value) {
        return value == null ? null : new PreflightGateResponse.DryRunSummary(id(value.getId()), value.getStatus(),
                value.getHeadCommit(), value.getResolvedTargetCommit(), iso(value.getUpdatedAt()));
    }

    private PreflightGateResponse.CqSummary cqSummary(PreflightCqReviewEntity value) {
        return value == null ? new PreflightGateResponse.CqSummary("PENDING", null, null, null)
                : new PreflightGateResponse.CqSummary(value.getDecision(), id(value.getReviewerUserId()),
                value.getReason(), iso(value.getReviewedAt()));
    }

    private String normalizeReason(String reason) {
        if (reason == null) return null;
        String value = reason.trim();
        return value.isEmpty() ? null : value;
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC).toInstant().toString();
    }
}
