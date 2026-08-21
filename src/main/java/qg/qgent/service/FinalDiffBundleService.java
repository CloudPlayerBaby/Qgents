package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitDiff;
import qg.qgent.orchestration.worker.WorkerGitDiffFile;
import qg.qgent.service.event.DeliveryStartedDomainEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Creates immutable, Task-level multi-repository Diff review batches.
 */
@Service
public class FinalDiffBundleService {
    /**
     * MR_FIRST 系统授权批次的交付租约时长；与 DiffReviewBatchService.DELIVERY_LEASE 保持一致，
     * 过期后由兜底扫描重新领取。
     */
    private static final java.time.Duration DELIVERY_LEASE = java.time.Duration.ofMinutes(30);
    private final TaskMapper tasks;
    private final TaskRunMapper runs;
    private final WorkspaceRepositoryMapper worktrees;
    private final DiffReviewBatchMapper batches;
    private final DiffMapper diffs;
    private final DiffFileMapper files;
    private final SandboxWorkerClient worker;
    private final DiffSnapshotStorage snapshots;
    private final EventService events;
    private final TransactionTemplate transactions;
    private WorkspaceMapper workspaces;
    /** 交付领域事件与浏览器 SSE 分离。 */
    private final ApplicationEventPublisher domainEvents;

    public FinalDiffBundleService(TaskMapper tasks, TaskRunMapper runs, WorkspaceRepositoryMapper worktrees,
                                  DiffReviewBatchMapper batches, DiffMapper diffs, DiffFileMapper files, SandboxWorkerClient worker,
                                  DiffSnapshotStorage snapshots, EventService events, ApplicationEventPublisher domainEvents,
                                  TransactionTemplate transactions) {
        this.tasks = tasks;
        this.runs = runs;
        this.worktrees = worktrees;
        this.batches = batches;
        this.diffs = diffs;
        this.files = files;
        this.worker = worker;
        this.snapshots = snapshots;
        this.events = events;
        this.domainEvents = domainEvents;
        this.transactions = transactions;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWorkspaceMapper(WorkspaceMapper workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * Worker calls happen before the short persistence transaction; no DB lock spans network I/O.
     */
    public UUID createPendingBatch(UUID projectId, UUID taskId, UUID finalCodingRunId) {
        TaskEntity task = requireTask(projectId, taskId);
        DiffReviewBatchEntity existing = batches.selectOne(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getTaskId, taskId)
                .eq(DiffReviewBatchEntity::getFinalCodingTaskRunId, finalCodingRunId).last("LIMIT 1"));
        if (existing != null) return restoreExistingPendingBatch(task, existing.getId());

        TaskRunEntity coding = requireCodingRun(taskId, finalCodingRunId);
        List<Snapshot> snapshotsToPersist = snapshotWorktrees(task);
        if (snapshotsToPersist.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "FINAL_DIFF_EMPTY", "No uncommitted changes are available for review");
        }
        snapshotsToPersist.sort(Comparator.comparing(value -> value.worktree().getProjectRepositoryId().toString()));
        String aggregateHash = aggregateHash(snapshotsToPersist);
        return transactions.execute(status -> persist(task, coding, snapshotsToPersist, aggregateHash));
    }

    /**
     * MR_FIRST 系统自动授权批次：与 {@link #createPendingBatch} 共享快照与幂等逻辑，差异仅在
     * 持久化状态——批次创建即 reviewStatus=ACCEPTED + confirmationSource=SYSTEM（表示按 MR_FIRST
     * 规则自动获准进入交付，不是用户确认），并直接分配交付操作 ID、claimToken 与租约，
     * Task 同事务置 DELIVERING 并发布 delivery.started 事件；事务提交后由交付模块（监听
     * DeliveryStartedDomainEvent）或兜底扫描消费该租约执行逐仓库 commit/push 交付。
     * Worker 快照调用发生在短事务之前，不持有数据库锁跨网络 I/O。
     */
    public UUID createSystemAcceptedBatch(UUID projectId, UUID taskId, UUID finalCodingRunId) {
        TaskEntity task = requireTask(projectId, taskId);
        DiffReviewBatchEntity existing = batches.selectOne(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getTaskId, taskId)
                .eq(DiffReviewBatchEntity::getFinalCodingTaskRunId, finalCodingRunId).last("LIMIT 1"));
        if (existing != null) return existing.getId();

        TaskRunEntity coding = requireCodingRun(taskId, finalCodingRunId);
        List<Snapshot> snapshotsToPersist = snapshotWorktrees(task);
        if (snapshotsToPersist.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "FINAL_DIFF_EMPTY", "No uncommitted changes are available for review");
        }
        snapshotsToPersist.sort(Comparator.comparing(value -> value.worktree().getProjectRepositoryId().toString()));
        String aggregateHash = aggregateHash(snapshotsToPersist);
        return transactions.execute(status ->
                persistSystemAccepted(task, coding, snapshotsToPersist, aggregateHash));
    }

    private TaskRunEntity requireCodingRun(UUID taskId, UUID finalCodingRunId) {
        TaskRunEntity coding = runs.selectById(finalCodingRunId);
        if (coding == null || !taskId.equals(coding.getTaskId()) || !"DEVELOPER".equals(coding.getRole())
                || !"SUCCEEDED".equals(coding.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "FINAL_CODING_RUN_INVALID",
                    "A successful final coding run is required");
        }
        return coding;
    }

    private List<Snapshot> snapshotWorktrees(TaskEntity task) {
        List<Snapshot> snapshotsToPersist = new ArrayList<>();
        for (WorkspaceRepositoryEntity worktree : worktrees.selectByWorkspace(task.getWorkspaceId())) {
            WorkerGitDiff snapshot = worker.createWorkspaceGitDiff(task.getWorkspaceId(), worktree.getProjectRepositoryId());
            validate(worktree, snapshot);
            if (snapshot.getPatch() == null || snapshot.getPatch().isBlank()) {
                continue;
            }
            snapshotsToPersist.add(new Snapshot(worktree, snapshot));
        }
        return snapshotsToPersist;
    }

    /**
     * MR_FIRST 批次持久化：短事务内原子写入 ACCEPTED+SYSTEM 批次、已接受的 Diffs、
     * 交付租约、Task=DELIVERING 与 delivery.started SSE 事件。同事务写入保证
     * 「任务进入 DELIVERING」与「交付意图（租约）」不分家；事件落库是事务事实，
     * SSE 客户端发送失败不回滚。
     */
    private UUID persistSystemAccepted(TaskEntity task, TaskRunEntity coding, List<Snapshot> values,
                                       String aggregateHash) {
        DiffReviewBatchEntity duplicate = batches.selectOne(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getTaskId, task.getId())
                .eq(DiffReviewBatchEntity::getFinalCodingTaskRunId, coding.getId()).last("LIMIT 1"));
        if (duplicate != null) return duplicate.getId();
        lockWorkspaceAndSupersedePending(task.getWorkspaceId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(UuidV7.next());
        batch.setProjectId(task.getProjectId());
        batch.setTaskId(task.getId());
        batch.setWorkspaceId(task.getWorkspaceId());
        batch.setFinalCodingTaskRunId(coding.getId());
        batch.setReviewStatus("ACCEPTED");
        // 系统按 MR_FIRST 规则自动授权交付；仅本内部流程可写 SYSTEM，前端与审计不得展示为「用户已确认」
        batch.setConfirmationSource("SYSTEM");
        batch.setReviewReason("MR_FIRST 自动交付（REVIEWER 通过）");
        batch.setReviewedAt(now);
        batch.setDeliveryStatus("DELIVERING");
        batch.setDeliveryOperationId(UUID.randomUUID().toString());
        batch.setDeliveryClaimToken(UUID.randomUUID().toString());
        batch.setDeliveryLeaseExpiresAt(now.plus(DELIVERY_LEASE));
        batch.setAggregateHash(aggregateHash);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batches.insert(batch);
        for (Snapshot value : values) {
            DiffEntity diff = new DiffEntity();
            diff.setId(UuidV7.next());
            diff.setProjectId(task.getProjectId());
            diff.setTaskId(task.getId());
            diff.setTaskRunId(coding.getId());
            diff.setTaskStepId(coding.getTaskStepId());
            diff.setWorkspaceId(task.getWorkspaceId());
            diff.setProjectRepositoryId(value.worktree().getProjectRepositoryId());
            diff.setBaseCommit(value.diff().getBaseCommit());
            diff.setSourceBranch(value.worktree().getSourceBranch());
            diff.setWorkingTreeHash(value.diff().getDiffHash());
            diff.setSnapshotKey(snapshots.store(diff.getId(), value.diff().getPatch()));
            diff.setHeadCommit(value.diff().getHeadCommit());
            diff.setStatus("ACCEPTED");
            diff.setReviewedAt(now);
            diff.setReviewBatchId(batch.getId());
            diff.setDeliveryStatus("NOT_STARTED");
            diff.setChangeStats(changeStats(value.diff().getFiles()));
            diff.setCreatedAt(now);
            diff.setUpdatedAt(now);
            diffs.insert(diff);
            saveFiles(diff.getId(), value.diff().getFiles(), now);
            events.publish(task.getProjectId(), task.getRequirementGroupId(), "diff.created", diff.getId().toString(),
                    Map.of("projectId", task.getProjectId(), "taskId", task.getId(), "diffId", diff.getId(),
                            "repositoryId", diff.getProjectRepositoryId(), "status", diff.getStatus()));
        }
        task.setStatus("DELIVERING");
        task.setUpdatedAt(now);
        tasks.updateById(task);
        events.publish(task.getProjectId(), task.getRequirementGroupId(), "task.updated", task.getId().toString(),
                TaskEventPayloads.taskUpdated(task));
        events.publish(task.getProjectId(), task.getRequirementGroupId(), "diff-review.created", batch.getId().toString(),
                Map.of("projectId", task.getProjectId(), "taskId", task.getId(), "reviewBatchId", batch.getId(),
                        "reviewStatus", batch.getReviewStatus(), "aggregateHash", aggregateHash));
        Map<String, Object> started = new LinkedHashMap<>();
        started.put("projectId", task.getProjectId());
        started.put("taskId", task.getId());
        started.put("reviewBatchId", batch.getId());
        started.put("deliveryMode", task.getDeliveryMode());
        if (task.getDeliveryReason() != null) {
            started.put("reason", task.getDeliveryReason());
        }
        started.put("operationId", batch.getDeliveryOperationId());
        // 由当前 TransactionTemplate 的 AFTER_COMMIT 监听器驱动交付，不能依赖 SSE 成功落库。
        domainEvents.publishEvent(new DeliveryStartedDomainEvent(task.getProjectId(), task.getId(), batch.getId(),
                batch.getDeliveryOperationId()));
        events.publish(task.getProjectId(), task.getRequirementGroupId(), "delivery.started",
                task.getId().toString(), started);
        return batch.getId();
    }

    private UUID persist(TaskEntity task, TaskRunEntity coding, List<Snapshot> values, String aggregateHash) {
        DiffReviewBatchEntity duplicate = batches.selectOne(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getTaskId, task.getId())
                .eq(DiffReviewBatchEntity::getFinalCodingTaskRunId, coding.getId()).last("LIMIT 1"));
        if (duplicate != null) return duplicate.getId();
        lockWorkspaceAndSupersedePending(task.getWorkspaceId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        DiffReviewBatchEntity batch = new DiffReviewBatchEntity();
        batch.setId(UuidV7.next());
        batch.setProjectId(task.getProjectId());
        batch.setTaskId(task.getId());
        batch.setWorkspaceId(task.getWorkspaceId());
        batch.setFinalCodingTaskRunId(coding.getId());
        batch.setReviewStatus("PENDING_CONFIRMATION");
        batch.setDeliveryStatus("NOT_STARTED");
        batch.setAggregateHash(aggregateHash);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batches.insert(batch);
        for (Snapshot value : values) {
            DiffEntity diff = new DiffEntity();
            diff.setId(UuidV7.next());
            diff.setProjectId(task.getProjectId());
            diff.setTaskId(task.getId());
            diff.setTaskRunId(coding.getId());
            diff.setTaskStepId(coding.getTaskStepId());
            diff.setWorkspaceId(task.getWorkspaceId());
            diff.setProjectRepositoryId(value.worktree().getProjectRepositoryId());
            diff.setBaseCommit(value.diff().getBaseCommit());
            diff.setSourceBranch(value.worktree().getSourceBranch());
            diff.setWorkingTreeHash(value.diff().getDiffHash());
            diff.setSnapshotKey(snapshots.store(diff.getId(), value.diff().getPatch()));
            diff.setHeadCommit(value.diff().getHeadCommit());
            diff.setStatus("PENDING_REVIEW");
            diff.setReviewBatchId(batch.getId());
            diff.setDeliveryStatus("NOT_STARTED");
            diff.setChangeStats(changeStats(value.diff().getFiles()));
            diff.setCreatedAt(now);
            diff.setUpdatedAt(now);
            diffs.insert(diff);
            saveFiles(diff.getId(), value.diff().getFiles(), now);
            events.publish(task.getProjectId(), task.getRequirementGroupId(), "diff.created", diff.getId().toString(),
                    Map.of("projectId", task.getProjectId(), "taskId", task.getId(), "diffId", diff.getId(),
                            "repositoryId", diff.getProjectRepositoryId(), "status", diff.getStatus()));
        }
        task.setStatus("WAITING_DIFF_CONFIRMATION");
        task.setUpdatedAt(now);
        tasks.updateById(task);
        events.publish(task.getProjectId(), task.getRequirementGroupId(), "task.updated", task.getId().toString(),
                TaskEventPayloads.taskUpdated(task));
        events.publish(task.getProjectId(), task.getRequirementGroupId(), "diff-review.created", batch.getId().toString(),
                Map.of("projectId", task.getProjectId(), "taskId", task.getId(), "reviewBatchId", batch.getId(),
                        "reviewStatus", batch.getReviewStatus(), "aggregateHash", aggregateHash));
        return batch.getId();
    }

    /**
     * The coding worker runs outside the transaction, so the final persistence
     * transaction must repeat the Workspace lock and supersede check.  This
     * closes the race where a continuation is created while an older run is
     * finishing its snapshot.
     */
    private void lockWorkspaceAndSupersedePending(UUID workspaceId) {
        if (workspaces != null && workspaceId != null) {
            workspaces.selectByIdForUpdate(workspaceId);
        }
        List<DiffReviewBatchEntity> pending = batches.selectPendingByWorkspaceForUpdate(workspaceId);
        if (pending == null || pending.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (DiffReviewBatchEntity old : pending) {
            old.setReviewStatus("SUPERSEDED");
            old.setReviewReason("被同一 Workspace 的后续修改取代");
            old.setUpdatedAt(now);
            batches.updateById(old);
            diffs.markReviewBatchSuperseded(old.getId(), now);
            // 与 TaskService.supersedePendingReviews 同口径：被取代批次所属的旧任务若仍停在
            // WAITING_DIFF_CONFIRMATION，必须一并迁到 FAILED，避免其永久卡在「未确认 Diff」。
            TaskEntity owner = tasks.selectByIdForUpdate(old.getTaskId());
            if (owner != null && "WAITING_DIFF_CONFIRMATION".equals(owner.getStatus())) {
                owner.setStatus("FAILED");
                owner.setFailureCode("DIFF_REVIEW_SUPERSEDED");
                owner.setFailureReason("最终 Diff 已被同一 Workspace 的后续修改取代，无法继续确认");
                owner.setFailureRetryable(false);
                owner.setFailureOccurredAt(now);
                owner.setUpdatedAt(now);
                tasks.updateById(owner);
                events.publish(owner.getProjectId(), owner.getRequirementGroupId(), "task.updated",
                        owner.getId().toString(), TaskEventPayloads.taskUpdated(owner));
            }
        }
    }

    /**
     * 兼容批次已提交但旧流程尚未更新 Task 状态的窗口；锁定后只恢复仍待确认的批次。
     */
    private UUID restoreExistingPendingBatch(TaskEntity task, UUID batchId) {
        return transactions.execute(status -> {
            DiffReviewBatchEntity batch = batches.selectByIdForUpdate(batchId);
            TaskEntity lockedTask = tasks.selectByIdForUpdate(task.getId());
            if (batch != null && lockedTask != null && "PENDING_CONFIRMATION".equals(batch.getReviewStatus())
                    && "RUNNING".equals(lockedTask.getStatus())) {
                lockedTask.setStatus("WAITING_DIFF_CONFIRMATION");
                lockedTask.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                tasks.updateById(lockedTask);
                events.publish(lockedTask.getProjectId(), lockedTask.getRequirementGroupId(), "task.updated",
                        lockedTask.getId().toString(), TaskEventPayloads.taskUpdated(lockedTask));
            }
            return batchId;
        });
    }

    private void saveFiles(UUID diffId, List<WorkerGitDiffFile> values, LocalDateTime now) {
        if (values == null) return;
        long sequence = 1;
        for (WorkerGitDiffFile source : values) {
            DiffFileEntity file = new DiffFileEntity();
            file.setId(UuidV7.next());
            file.setDiffId(diffId);
            file.setSequenceNo(sequence++);
            file.setPath(source.getPath());
            file.setChangeType(source.getChangeType());
            file.setAdditions(source.getAdditions());
            file.setDeletions(source.getDeletions());
            file.setBinaryFlag(source.isBinary());
            file.setHunks(source.getHunks() == null ? List.of() : new ArrayList<>(source.getHunks()));
            file.setCreatedAt(now);
            files.insert(file);
        }
    }

    private void validate(WorkspaceRepositoryEntity worktree, WorkerGitDiff diff) {
        if (diff == null || !validSha(diff.getBaseCommit()) || !validSha(diff.getHeadCommit())
                || diff.getDiffHash() == null || !diff.getDiffHash().matches("sha256:[0-9a-f]{64}")
                || !worktree.getBaseCommit().equalsIgnoreCase(diff.getBaseCommit())) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKER_DIFF_SNAPSHOT_INVALID",
                    "Worker returned a Diff that does not match the Workspace baseline");
        }
    }

    private String aggregateHash(List<Snapshot> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Snapshot value : values) {
                String item = value.worktree().getProjectRepositoryId() + "\n" + value.diff().getBaseCommit() + "\n"
                        + value.diff().getHeadCommit() + "\n" + value.diff().getDiffHash() + "\n";
                digest.update(item.getBytes(StandardCharsets.UTF_8));
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate final Diff aggregate hash", exception);
        }
    }

    private Map<String, Object> changeStats(List<WorkerGitDiffFile> values) {
        int additions = values == null ? 0 : values.stream().mapToInt(WorkerGitDiffFile::getAdditions).sum();
        int deletions = values == null ? 0 : values.stream().mapToInt(WorkerGitDiffFile::getDeletions).sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", values == null ? 0 : values.size());
        result.put("additions", additions);
        result.put("deletions", deletions);
        return result;
    }

    private TaskEntity requireTask(UUID projectId, UUID taskId) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "Task does not exist or is not visible");
        }
        return task;
    }

    private boolean validSha(String value) {
        return value != null && value.matches("[0-9a-fA-F]{40,64}");
    }

    private record Snapshot(WorkspaceRepositoryEntity worktree, WorkerGitDiff diff) {
    }
}
