package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.DiffFileEntity;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.DiffFileMapper;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitDiff;
import qg.qgent.orchestration.worker.WorkerGitDiffFile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Creates immutable, Task-level multi-repository Diff review batches. */
@Service
public class FinalDiffBundleService {
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

    public FinalDiffBundleService(TaskMapper tasks, TaskRunMapper runs, WorkspaceRepositoryMapper worktrees,
            DiffReviewBatchMapper batches, DiffMapper diffs, DiffFileMapper files, SandboxWorkerClient worker,
            DiffSnapshotStorage snapshots, EventService events, TransactionTemplate transactions) {
        this.tasks = tasks;
        this.runs = runs;
        this.worktrees = worktrees;
        this.batches = batches;
        this.diffs = diffs;
        this.files = files;
        this.worker = worker;
        this.snapshots = snapshots;
        this.events = events;
        this.transactions = transactions;
    }

    /** Worker calls happen before the short persistence transaction; no DB lock spans network I/O. */
    public UUID createPendingBatch(UUID projectId, UUID taskId, UUID finalCodingRunId) {
        TaskEntity task = requireTask(projectId, taskId);
        DiffReviewBatchEntity existing = batches.selectOne(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getTaskId, taskId)
                .eq(DiffReviewBatchEntity::getFinalCodingTaskRunId, finalCodingRunId).last("LIMIT 1"));
        if (existing != null) return restoreExistingPendingBatch(task, existing.getId());

        TaskRunEntity coding = runs.selectById(finalCodingRunId);
        if (coding == null || !taskId.equals(coding.getTaskId()) || !"DEVELOPER".equals(coding.getRole())
                || !"SUCCEEDED".equals(coding.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "FINAL_CODING_RUN_INVALID",
                    "A successful final coding run is required");
        }
        List<Snapshot> snapshotsToPersist = new ArrayList<>();
        for (WorkspaceRepositoryEntity worktree : worktrees.selectByWorkspace(task.getWorkspaceId())) {
            WorkerGitDiff snapshot = worker.createWorkspaceGitDiff(task.getWorkspaceId(), worktree.getProjectRepositoryId());
            validate(worktree, snapshot);
            if (snapshot.getPatch() == null || snapshot.getPatch().isBlank()) {
                continue;
            }
            snapshotsToPersist.add(new Snapshot(worktree, snapshot));
        }
        if (snapshotsToPersist.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "FINAL_DIFF_EMPTY", "No uncommitted changes are available for review");
        }
        snapshotsToPersist.sort(Comparator.comparing(value -> value.worktree().getProjectRepositoryId().toString()));
        String aggregateHash = aggregateHash(snapshotsToPersist);
        return transactions.execute(status -> persist(task, coding, snapshotsToPersist, aggregateHash));
    }

    private UUID persist(TaskEntity task, TaskRunEntity coding, List<Snapshot> values, String aggregateHash) {
        DiffReviewBatchEntity duplicate = batches.selectOne(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getTaskId, task.getId())
                .eq(DiffReviewBatchEntity::getFinalCodingTaskRunId, coding.getId()).last("LIMIT 1"));
        if (duplicate != null) return duplicate.getId();
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

    /** 兼容批次已提交但旧流程尚未更新 Task 状态的窗口；锁定后只恢复仍待确认的批次。 */
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
