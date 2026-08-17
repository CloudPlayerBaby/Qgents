package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
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
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitResolveRequest;
import qg.qgent.orchestration.worker.WorkerGitResolveResponse;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
    private final SandboxWorkerClient worker;
    private final EventService events;

    public PreflightGateService(DryRunMapper dryRuns, PreflightCqReviewMapper cqReviews, TaskMapper tasks,
                                WorkspaceRepositoryMapper worktrees, ProjectRepositoryMapper repositories,
                                ProjectAccessService access, SandboxWorkerClient worker, EventService events) {
        this.dryRuns = dryRuns;
        this.cqReviews = cqReviews;
        this.tasks = tasks;
        this.worktrees = worktrees;
        this.repositories = repositories;
        this.access = access;
        this.worker = worker;
        this.events = events;
    }

    public PreflightGateResponse get(UUID projectId, UUID taskId, UUID repositoryId, String targetBranch, UUID actor) {
        access.requireProjectMember(projectId, actor);
        TaskEntity task = requireTask(projectId, taskId);
        WorkspaceRepositoryEntity worktree = requireWorktree(task, repositoryId);
        String targetCommit = resolveTargetCommit(projectId, repositoryId, targetBranch);
        return evaluate(task, worktree, repositoryId, targetBranch, targetCommit);
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
        PreflightGateResponse gate = evaluate(task, worktree, repositoryId, targetBranch, targetCommit);
        if (!"PASSED".equals(gate.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "MR_PREFLIGHT_NOT_PASSED",
                    "MR 创建前预检未通过", gate.getBlockers().stream()
                    .map(blocker -> java.util.Map.of("code", blocker)).toList());
        }
    }

    /** 在事务外解析当前目标分支，并校验仓库归属。 */
    public String resolveTargetCommit(UUID projectId, UUID repositoryId, String targetBranch) {
        ProjectRepositoryEntity repository = repositories.selectById(repositoryId);
        if (repository == null || !projectId.equals(repository.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REPOSITORY_NOT_FOUND", "仓库不存在或不可见");
        }
        if (targetBranch == null || targetBranch.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TARGET_BRANCH", "目标分支不能为空");
        }
        WorkerGitResolveRequest request = new WorkerGitResolveRequest();
        request.setRepositoryId(repositoryId);
        request.setRef(targetBranch.trim());
        WorkerGitResolveResponse response = worker.resolveGitRef(request);
        String commit = response == null ? null : response.getCommitSha();
        if (commit == null || !commit.matches("[0-9a-fA-F]{40,64}")) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GIT_RESOLUTION_INVALID", "Sandbox Worker 未返回有效的目标提交 SHA");
        }
        return commit.toLowerCase(Locale.ROOT);
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
        cqReviews.insert(review);
        publishUpdated(task, dryRun, decision);
        return evaluate(task, worktree, dryRun.getProjectRepositoryId(), dryRun.getTargetBranch(), currentTarget);
    }

    private PreflightGateResponse evaluate(TaskEntity task, WorkspaceRepositoryEntity worktree, UUID repositoryId,
                                           String targetBranch, String targetCommit) {
        String sourceCommit = worktree.getHeadCommit();
        List<String> blockers = new ArrayList<>();
        DryRunEntity dryRun = sourceCommit == null ? null : latestDryRun(task.getProjectId(), task.getId(), repositoryId,
                sourceCommit, targetBranch, targetCommit);
        if (dryRun == null) blockers.add("DRY_RUN_MISSING");
        else if (!"PASSED".equals(dryRun.getStatus())) blockers.add("DRY_RUN_" + dryRun.getStatus());

        PreflightCqReviewEntity cq = dryRun == null ? null : latestCq(dryRun.getId(), sourceCommit, targetCommit);
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
                .orderByDesc(DryRunEntity::getCreatedAt).last("LIMIT 1"));
    }

    private PreflightCqReviewEntity latestCq(UUID dryRunId, String sourceCommit, String targetCommit) {
        return cqReviews.selectOne(Wrappers.<PreflightCqReviewEntity>lambdaQuery()
                .eq(PreflightCqReviewEntity::getDryRunId, dryRunId)
                .eq(PreflightCqReviewEntity::getSourceCommit, sourceCommit)
                .eq(PreflightCqReviewEntity::getTargetCommit, targetCommit)
                .orderByDesc(PreflightCqReviewEntity::getReviewedAt).last("LIMIT 1"));
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
