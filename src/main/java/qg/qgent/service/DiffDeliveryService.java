package qg.qgent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceEntity;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.WorkspaceMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitCommitRequest;
import qg.qgent.orchestration.worker.WorkerGitCommitResponse;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 复用 Worker 对精确 Diff 快照的校验与真实 Commit 落库流程。
 */
@Service
@Slf4j
public class DiffDeliveryService {
    private final DiffMapper diffs;
    private final WorkspaceMapper workspaces;
    private final WorkspaceRepositoryMapper worktrees;
    private final SandboxWorkerClient worker;
    private final TransactionTemplate transactions;
    private final DiffReviewBatchMapper batches;
    private WorkspaceWriteLeaseService workspaceWriteLeases;
    /** 真实 Worker commit 前的工作分支 MR 锁定门禁。 */
    private WorkBranchDevelopmentGuard developmentGuard;

    public DiffDeliveryService(DiffMapper diffs, WorkspaceMapper workspaces,
                               WorkspaceRepositoryMapper worktrees, SandboxWorkerClient worker, TransactionTemplate transactions,
                               DiffReviewBatchMapper batches) {
        this.diffs = diffs;
        this.workspaces = workspaces;
        this.worktrees = worktrees;
        this.worker = worker;
        this.transactions = transactions;
        this.batches = batches;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWorkspaceWriteLeases(WorkspaceWriteLeaseService workspaceWriteLeases) {
        this.workspaceWriteLeases = workspaceWriteLeases;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDevelopmentGuard(WorkBranchDevelopmentGuard developmentGuard) {
        this.developmentGuard = developmentGuard;
    }

    /**
     * 在 Worker 中校验预期 HEAD 与 Diff hash 后创建 Commit，不持有数据库事务。
     */
    public String commitVerified(TaskEntity task, DiffEntity diff) {
        if (developmentGuard != null) {
            developmentGuard.requireBranchWritable(task.getProjectId(), diff.getProjectRepositoryId(),
                    diff.getSourceBranch(), "DIFF_DELIVERY_BLOCKED_BY_OPEN_MR",
                    "当前工作分支存在未合并的 MR，不能继续进行 Diff 交付");
        }
        String expectedHead = diff.getHeadCommit() == null || diff.getHeadCommit().isBlank()
                ? diff.getBaseCommit() : diff.getHeadCommit();
        if (expectedHead == null || expectedHead.isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_HEAD_MISSING", "Diff 缺少可校验的 HEAD");
        }
        log.info("repository commit started projectId={} taskId={} diffId={} repositoryId={} workspaceId={}",
                task.getProjectId(), task.getId(), diff.getId(), diff.getProjectRepositoryId(), task.getWorkspaceId());
        WorkerGitCommitResponse result = worker.commitWorkspaceDiff(task.getWorkspaceId(),
                diff.getProjectRepositoryId(), new WorkerGitCommitRequest()
                        .setExpectedHeadCommit(expectedHead)
                        .setExpectedDiffHash(diff.getWorkingTreeHash())
                        .setOperationId(operationId(diff))
                        .setMessage(commitMessage(task)));
        if (result == null || result.getCommitSha() == null || result.getCommitSha().isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WORKER_COMMIT_FAILED", "Worker 未返回真实 Commit SHA");
        }
        log.info("repository commit completed projectId={} taskId={} diffId={} repositoryId={} commitSha={}",
                task.getProjectId(), task.getId(), diff.getId(), diff.getProjectRepositoryId(), result.getCommitSha());
        return result.getCommitSha();
    }

    /**
     * 保存批次交付已经由 Worker 创建的真实 Commit。
     */
    public void recordCommitted(TaskEntity task, UUID diffId, String commitSha, UUID batchId, String batchToken) {
        transactions.executeWithoutResult(status -> {
            requireBatchClaim(batchId, batchToken);
            recordCommit(task, diffId, commitSha, null, null, batchId);
        });
    }

    /**
     * 非批次 Diff 仅在真实 Commit 成功后同时记录审核决定和 Commit 事实。
     */
    public DiffEntity acceptNonBatch(TaskEntity task, DiffEntity snapshot, UUID actor) {
        WorkspaceWriteLease workspaceLease = acquireWorkspaceWriteLease(task);
        try {
            DiffEntity claimed = transactions.execute(status -> claimNonBatch(task, snapshot.getId()));
            String commitSha;
            try {
                renewWorkspaceWriteLease(workspaceLease);
                commitSha = commitVerified(task, claimed);
            } catch (RuntimeException failure) {
                log.error("non-batch repository commit failed projectId={} taskId={} diffId={} repositoryId={} code={} message={}",
                        task.getProjectId(), task.getId(), claimed.getId(), claimed.getProjectRepositoryId(),
                        failure instanceof ApiException api ? api.code() : "WORKER_COMMIT_FAILED", failure.getMessage(), failure);
                recordFailure(claimed.getId(), claimed.getDeliveryClaimToken(), failure);
                throw failure;
            }
            return transactions.execute(status -> recordCommit(task, claimed.getId(), commitSha, actor,
                    claimed.getDeliveryClaimToken(), null));
        } finally {
            releaseWorkspaceWriteLease(workspaceLease);
        }
    }

    /**
     * 在短事务内拒绝非批次 Diff，不触发任何 Git 操作。
     */
    public DiffEntity rejectNonBatch(TaskEntity task, DiffEntity snapshot, UUID actor, String reason) {
        return transactions.execute(status -> {
            DiffEntity current = requirePendingNonBatchForUpdate(task, snapshot.getId());
            if (current.getDeliveryOperationId() != null || !"NOT_STARTED".equals(current.getDeliveryStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "DIFF_DELIVERY_IN_PROGRESS",
                        "Diff 正在创建 Commit，不能同时拒绝");
            }
            requireWorkspace(current);
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            current.setStatus("REJECTED");
            current.setReviewedBy(actor);
            current.setReviewReason(reason);
            current.setReviewedAt(now);
            current.setUpdatedAt(now);
            diffs.updateById(current);
            return current;
        });
    }

    private DiffEntity recordCommit(TaskEntity task, UUID diffId, String commitSha, UUID actor, String claimToken,
                                    UUID expectedBatchId) {
        DiffEntity current = diffs.selectByIdForUpdate(diffId);
        requireContext(task, current);
        if (expectedBatchId != null && (!expectedBatchId.equals(current.getReviewBatchId())
                || !"ACCEPTED".equals(current.getStatus()))) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_BATCH_CONTEXT_INVALID",
                    "Diff is not accepted by the claimed review batch");
        }
        if (claimToken != null && !claimToken.equals(current.getDeliveryClaimToken())) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_DELIVERY_CLAIM_LOST",
                    "Diff delivery claim is no longer active");
        }
        if (actor != null && (current.getReviewBatchId() != null
                || !"PENDING_REVIEW".equals(current.getStatus())
                || !"DELIVERING".equals(current.getDeliveryStatus()))) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_NOT_DECIDABLE", "Diff 已不能单独审核");
        }
        requireWorkspace(current);
        if (worktrees.updateHeadCommit(task.getWorkspaceId(), current.getProjectRepositoryId(), commitSha) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_REPOSITORY_NOT_FOUND", "Workspace 中不存在目标仓库");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        current.setHeadCommit(commitSha);
        current.setDeliveryStatus("COMMITTED");
        current.setDeliveryFailureCode(null);
        current.setDeliveryFailureReason(null);
        current.setDeliveryClaimToken(null);
        current.setDeliveryLeaseExpiresAt(null);
        if (actor != null) {
            current.setStatus("ACCEPTED");
            current.setReviewedBy(actor);
            current.setReviewedAt(now);
        }
        current.setUpdatedAt(now);
        diffs.updateById(current);
        return current;
    }

    private WorkspaceWriteLease acquireWorkspaceWriteLease(TaskEntity task) {
        return workspaceWriteLeases == null ? null
                : workspaceWriteLeases.acquire(task.getProjectId(), task.getWorkspaceId(), task.getId());
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

    private DiffEntity requirePendingNonBatch(TaskEntity task, UUID diffId) {
        DiffEntity current = requireContext(task, diffId);
        if (current.getReviewBatchId() != null || !"PENDING_REVIEW".equals(current.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_NOT_DECIDABLE", "Diff 已不能单独审核");
        }
        return current;
    }

    private DiffEntity requirePendingNonBatchForUpdate(TaskEntity task, UUID diffId) {
        DiffEntity current = diffs.selectByIdForUpdate(diffId);
        requireContext(task, current);
        if (current.getReviewBatchId() != null || !"PENDING_REVIEW".equals(current.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_NOT_DECIDABLE", "Diff 已不能单独审核");
        }
        return current;
    }

    private DiffEntity claimNonBatch(TaskEntity task, UUID diffId) {
        DiffEntity current = diffs.selectByIdForUpdate(diffId);
        if (current == null || !task.getProjectId().equals(current.getProjectId())
                || !task.getWorkspaceId().equals(current.getWorkspaceId()) || current.getReviewBatchId() != null
                || !"PENDING_REVIEW".equals(current.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_NOT_DECIDABLE", "Diff 已不能单独审核");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if ("DELIVERING".equals(current.getDeliveryStatus()) && current.getDeliveryLeaseExpiresAt() != null
                && current.getDeliveryLeaseExpiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_DELIVERY_IN_PROGRESS", "Diff 正在创建 Commit");
        }
        if (current.getDeliveryOperationId() == null) current.setDeliveryOperationId(UUID.randomUUID().toString());
        current.setDeliveryClaimToken(UUID.randomUUID().toString());
        current.setDeliveryStatus("DELIVERING");
        current.setDeliveryFailureCode(null);
        current.setDeliveryFailureReason(null);
        current.setDeliveryLeaseExpiresAt(now.plus(Duration.ofMinutes(10)));
        current.setUpdatedAt(now);
        diffs.updateById(current);
        return current;
    }

    private DiffEntity requireContext(TaskEntity task, UUID diffId) {
        DiffEntity current = diffs.selectById(diffId);
        requireContext(task, current);
        return current;
    }

    private void requireContext(TaskEntity task, DiffEntity current) {
        if (current == null || !task.getProjectId().equals(current.getProjectId())
                || !task.getWorkspaceId().equals(current.getWorkspaceId())) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_TASK_CONTEXT_INVALID", "Diff 上下文已变化");
        }
    }

    private void requireWorkspace(DiffEntity current) {
        WorkspaceEntity workspace = workspaces.selectByIdForUpdate(current.getWorkspaceId());
        if (workspace == null || !current.getProjectId().equals(workspace.getProjectId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DIFF_WORKSPACE_CONTEXT_INVALID",
                    "Diff Workspace 不属于当前项目");
        }
    }

    private void recordFailure(UUID diffId, String claimToken, RuntimeException failure) {
        transactions.executeWithoutResult(status -> {
            DiffEntity current = diffs.selectByIdForUpdate(diffId);
            if (current == null || "COMMITTED".equals(current.getDeliveryStatus())
                    || "MR_CREATED".equals(current.getDeliveryStatus())
                    || claimToken == null || !claimToken.equals(current.getDeliveryClaimToken())) return;
            current.setDeliveryStatus("FAILED");
            current.setDeliveryFailureCode(failure instanceof ApiException api
                    ? api.code() : "WORKER_COMMIT_FAILED");
            current.setDeliveryFailureReason(safeReason(failure));
            current.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            current.setDeliveryClaimToken(null);
            current.setDeliveryLeaseExpiresAt(null);
            diffs.updateById(current);
        });
    }

    private void requireBatchClaim(UUID batchId, String batchToken) {
        if (batches == null || batchId == null || batchToken == null) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_DELIVERY_CLAIM_LOST",
                    "Final Diff delivery claim is no longer active");
        }
        qg.qgent.entity.DiffReviewBatchEntity batch = batches.selectByIdForUpdate(batchId);
        if (batch == null || !"DELIVERING".equals(batch.getDeliveryStatus())
                || !batchToken.equals(batch.getDeliveryClaimToken())) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_DELIVERY_CLAIM_LOST",
                    "Final Diff delivery claim is no longer active");
        }
    }

    private String safeReason(RuntimeException failure) {
        String reason = failure instanceof ApiException api ? api.code() : "Delivery operation failed";
        return reason.substring(0, Math.min(reason.length(), 256));
    }

    private String operationId(DiffEntity diff) {
        if (diff.getDeliveryOperationId() != null && !diff.getDeliveryOperationId().isBlank()) {
            return diff.getDeliveryOperationId();
        }
        return UUID.nameUUIDFromBytes(("qgents-diff:" + diff.getId()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String commitMessage(TaskEntity task) {
        String title = task.getTitle() == null ? "task changes" : task.getTitle().replaceAll("[\\r\\n]+", " ").trim();
        String message = "feat(task): " + title;
        return message.substring(0, Math.min(500, message.length()));
    }
}
