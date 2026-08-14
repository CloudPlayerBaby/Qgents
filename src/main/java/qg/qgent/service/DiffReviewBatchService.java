package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.dto.DiffListItemResponse;
import qg.qgent.dto.DiffReviewBatchResponse;
import qg.qgent.dto.DiffReviewPatchResponse;
import qg.qgent.dto.MergeRequestBrief;
import qg.qgent.dto.MergeRequestCreateRequest;
import qg.qgent.dto.RepositoryDelivery;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.DiffReviewBatchMapper;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitCommitRequest;
import qg.qgent.orchestration.worker.WorkerGitCommitResponse;
import qg.qgent.orchestration.worker.WorkerGitDiff;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Applies one Task-level review decision, then delivers each repository independently. */
@Service
public class DiffReviewBatchService {
    private final DiffReviewBatchMapper batches;
    private final DiffMapper diffs;
    private final TaskMapper tasks;
    private final WorkspaceRepositoryMapper worktrees;
    private final ProjectRepositoryMapper repositories;
    private final GitHubRepositoryMapper githubRepositories;
    private final MergeRequestMapper mergeRequestMapper;
    private final SandboxWorkerClient worker;
    private final MergeRequestService mergeRequests;
    private final ProjectAccessService access;
    private final EventService events;
    private final TransactionTemplate transactions;
    private final DiffSnapshotStorage snapshots;

    public DiffReviewBatchService(DiffReviewBatchMapper batches, DiffMapper diffs, TaskMapper tasks,
            WorkspaceRepositoryMapper worktrees, ProjectRepositoryMapper repositories,
            GitHubRepositoryMapper githubRepositories, MergeRequestMapper mergeRequestMapper, SandboxWorkerClient worker,
            MergeRequestService mergeRequests, ProjectAccessService access, EventService events,
            TransactionTemplate transactions, DiffSnapshotStorage snapshots) {
        this.batches = batches;
        this.diffs = diffs;
        this.tasks = tasks;
        this.worktrees = worktrees;
        this.repositories = repositories;
        this.githubRepositories = githubRepositories;
        this.mergeRequestMapper = mergeRequestMapper;
        this.worker = worker;
        this.mergeRequests = mergeRequests;
        this.access = access;
        this.events = events;
        this.transactions = transactions;
        this.snapshots = snapshots;
    }

    public DiffReviewBatchResponse get(UUID projectId, UUID taskId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        DiffReviewBatchEntity batch = latest(projectId, taskId);
        return response(batch, diffs(batch.getId()));
    }

    /** Returns one immutable patch only after project membership and batch ownership checks. */
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

    /** A short DB transaction claims delivery; Worker calls deliberately occur after it commits. */
    public DiffReviewBatchResponse confirm(UUID projectId, UUID taskId, UUID actor) {
        TaskEntity task = requireTask(projectId, taskId);
        requireOwnerOrAdmin(task, actor);
        DiffReviewBatchEntity batch = transactions.execute(status -> claim(task));
        List<DiffEntity> values = diffs(batch.getId());
        try {
            preflight(task, values);
        } catch (RuntimeException failure) {
            markBatchDelivery(batch.getId(), "FAILED", null);
            throw failure;
        }
        transactions.execute(status -> {
            accept(task, batch.getId(), actor);
            return null;
        });
        for (DiffEntity diff : diffs(batch.getId())) {
            deliver(task, diff, actor);
        }
        finish(task, batch.getId());
        DiffReviewBatchEntity result = requireBatch(batch.getId());
        return response(result, diffs(result.getId()));
    }

    public DiffReviewBatchResponse reject(UUID projectId, UUID taskId, UUID actor, String reason) {
        TaskEntity task = requireTask(projectId, taskId);
        requireOwnerOrAdmin(task, actor);
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DIFF_REJECT_REASON_REQUIRED", "A rejection reason is required");
        }
        DiffReviewBatchEntity batch = transactions.execute(status -> {
            DiffReviewBatchEntity locked = latestForUpdate(projectId, taskId);
            if (!"PENDING_CONFIRMATION".equals(locked.getReviewStatus())) {
                throw new ApiException(HttpStatus.CONFLICT, "DIFF_REVIEW_NOT_DECIDABLE", "Final Diff is not awaiting review");
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
            events.publish(projectId, task.getRequirementGroupId(), "diff-review.rejected", locked.getId().toString(),
                    Map.of("projectId", projectId, "taskId", taskId, "reviewBatchId", locked.getId()));
            return locked;
        });
        return response(batch, diffs(batch.getId()));
    }

    public DiffReviewBatchResponse retryDelivery(UUID projectId, UUID taskId, UUID actor) {
        TaskEntity task = requireTask(projectId, taskId);
        requireOwnerOrAdmin(task, actor);
        DiffReviewBatchEntity batch = transactions.execute(status -> claimRetry(projectId, taskId));
        for (DiffEntity diff : diffs(batch.getId())) {
            if (!"MR_CREATED".equals(diff.getDeliveryStatus())) deliver(task, diff, actor);
        }
        finish(task, batch.getId());
        DiffReviewBatchEntity result = requireBatch(batch.getId());
        return response(result, diffs(result.getId()));
    }

    private DiffReviewBatchEntity claim(TaskEntity task) {
        DiffReviewBatchEntity batch = latestForUpdate(task.getProjectId(), task.getId());
        if (!"PENDING_CONFIRMATION".equals(batch.getReviewStatus()) || !"NOT_STARTED".equals(batch.getDeliveryStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_REVIEW_NOT_DECIDABLE", "Final Diff is not awaiting confirmation");
        }
        batch.setDeliveryStatus("DELIVERING");
        batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        batches.updateById(batch);
        return batch;
    }

    private DiffReviewBatchEntity claimRetry(UUID projectId, UUID taskId) {
        DiffReviewBatchEntity batch = latestForUpdate(projectId, taskId);
        if (!"ACCEPTED".equals(batch.getReviewStatus()) || "DELIVERED".equals(batch.getDeliveryStatus())
                || "DELIVERING".equals(batch.getDeliveryStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_DELIVERY_NOT_RETRYABLE",
                    "Final Diff delivery is not retryable");
        }
        batch.setDeliveryStatus("DELIVERING");
        batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
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

    private void accept(TaskEntity task, UUID batchId, UUID actor) {
        DiffReviewBatchEntity batch = requireBatch(batchId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        batch.setReviewStatus("ACCEPTED");
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
        events.publish(task.getProjectId(), task.getRequirementGroupId(), "diff-review.confirmed", batchId.toString(),
                Map.of("projectId", task.getProjectId(), "taskId", task.getId(), "reviewBatchId", batchId));
    }

    private void deliver(TaskEntity task, DiffEntity diff, UUID actor) {
        UUID diffId = diff.getId();
        try {
            if (!"COMMITTED".equals(diff.getDeliveryStatus()) && !"MR_CREATED".equals(diff.getDeliveryStatus())) {
                WorkerGitCommitResponse committed = worker.commitWorkspaceDiff(task.getWorkspaceId(),
                        diff.getProjectRepositoryId(), new WorkerGitCommitRequest()
                                .setExpectedHeadCommit(diff.getHeadCommit())
                                .setExpectedDiffHash(diff.getWorkingTreeHash())
                                .setMessage(commitMessage(task)));
                if (committed == null || committed.getCommitSha() == null) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "WORKER_COMMIT_FAILED", "Worker did not return a commit SHA");
                }
                transactions.execute(status -> {
                    markCommitted(task, diffId, committed.getCommitSha());
                    return null;
                });
                diff = diffs.selectById(diffId);
            }
            if (!"MR_CREATED".equals(diff.getDeliveryStatus())) {
                ProjectRepositoryEntity repository = repositories.selectById(diff.getProjectRepositoryId());
                if (repository == null || !task.getProjectId().equals(repository.getProjectId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "REPOSITORY_NOT_IN_PROJECT", "Repository is no longer bound to the Task project");
                }
                MergeRequestCreateRequest request = new MergeRequestCreateRequest();
                request.setTaskId(task.getId());
                request.setRepositoryId(repository.getId());
                request.setTargetBranch(repository.getDefaultBranch());
                request.setTitle(task.getTitle());
                mergeRequests.create(task.getProjectId(), actor, request);
                transactions.execute(status -> {
                    markDelivered(task, diffId);
                    return null;
                });
            }
        } catch (RuntimeException failure) {
            markDiffFailure(task, diffId, failure.getMessage());
        }
    }

    private void markCommitted(TaskEntity task, UUID diffId, String commitSha) {
        DiffEntity current = diffs.selectById(diffId);
        worktrees.updateHeadCommit(task.getWorkspaceId(), current.getProjectRepositoryId(), commitSha);
        current.setHeadCommit(commitSha);
        current.setDeliveryStatus("COMMITTED");
        current.setDeliveryFailureReason(null);
        current.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        diffs.updateById(current);
    }

    private void markDelivered(TaskEntity task, UUID diffId) {
        DiffEntity current = diffs.selectById(diffId);
        current.setDeliveryStatus("MR_CREATED");
        current.setDeliveryFailureReason(null);
        current.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        diffs.updateById(current);
        events.publish(task.getProjectId(), task.getRequirementGroupId(), "delivery.repository.updated", diffId.toString(),
                Map.of("projectId", task.getProjectId(), "taskId", task.getId(), "diffId", diffId,
                        "deliveryStatus", "MR_CREATED"));
    }

    private void markDiffFailure(TaskEntity task, UUID diffId, String reason) {
        transactions.execute(status -> {
            DiffEntity current = diffs.selectById(diffId);
            current.setDeliveryStatus("FAILED");
            current.setDeliveryFailureReason(reason == null ? "delivery failed" : reason);
            current.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            diffs.updateById(current);
            return null;
        });
    }

    private void finish(TaskEntity task, UUID batchId) {
        transactions.execute(status -> {
            List<DiffEntity> values = diffs(batchId);
            boolean allDelivered = values.stream().allMatch(value -> "MR_CREATED".equals(value.getDeliveryStatus()));
            boolean anyDelivered = values.stream().anyMatch(value -> "MR_CREATED".equals(value.getDeliveryStatus()));
            DiffReviewBatchEntity batch = requireBatch(batchId);
            batch.setDeliveryStatus(allDelivered ? "DELIVERED" : anyDelivered ? "PARTIALLY_DELIVERED" : "FAILED");
            batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            batches.updateById(batch);
            task.setStatus(allDelivered ? "SUCCEEDED" : "DELIVERY_FAILED");
            task.setUpdatedAt(batch.getUpdatedAt());
            tasks.updateById(task);
            events.publish(task.getProjectId(), task.getRequirementGroupId(), allDelivered ? "delivery.completed" : "delivery.failed",
                    batchId.toString(), Map.of("projectId", task.getProjectId(), "taskId", task.getId(),
                            "reviewBatchId", batchId, "deliveryStatus", batch.getDeliveryStatus()));
            return null;
        });
    }

    private void markBatchDelivery(UUID batchId, String deliveryStatus, String reason) {
        transactions.execute(status -> {
            DiffReviewBatchEntity batch = requireBatch(batchId);
            batch.setDeliveryStatus(deliveryStatus);
            batch.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            batches.updateById(batch);
            return null;
        });
    }

    private DiffReviewBatchEntity latest(UUID projectId, UUID taskId) {
        DiffReviewBatchEntity batch = batches.selectOne(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getProjectId, projectId).eq(DiffReviewBatchEntity::getTaskId, taskId)
                .orderByDesc(DiffReviewBatchEntity::getCreatedAt).last("LIMIT 1"));
        if (batch == null) throw new ApiException(HttpStatus.NOT_FOUND, "DIFF_REVIEW_NOT_FOUND", "Final Diff review does not exist");
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
        if (batch == null) throw new ApiException(HttpStatus.NOT_FOUND, "DIFF_REVIEW_NOT_FOUND", "Final Diff review does not exist");
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
                batch.getDeliveryStatus(), batch.getAggregateHash(), batch.getReviewReason(), items,
                repositoryDeliveries(batch.getTaskId(), values));
    }

    /** 组装逐仓库交付详情（成功/失败原因/MR 摘要），仓库与 MR 批量加载，避免逐 Diff N+1。 */
    private List<RepositoryDelivery> repositoryDeliveries(UUID taskId, List<DiffEntity> values) {
        if (values.isEmpty()) {
            return List.of();
        }
        List<UUID> bindingIds = values.stream().map(DiffEntity::getProjectRepositoryId).distinct().toList();
        Map<UUID, ProjectRepositoryEntity> bindingById = repositories
                .selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery().in(ProjectRepositoryEntity::getId, bindingIds))
                .stream().collect(Collectors.toMap(ProjectRepositoryEntity::getId, Function.identity()));
        List<UUID> githubIds = bindingById.values().stream().map(ProjectRepositoryEntity::getRepositoryId).distinct()
                .toList();
        Map<UUID, GitHubRepositoryEntity> githubById = githubIds.isEmpty() ? Map.of()
                : githubRepositories
                        .selectList(Wrappers.<GitHubRepositoryEntity>lambdaQuery().in(GitHubRepositoryEntity::getId, githubIds))
                        .stream().collect(Collectors.toMap(GitHubRepositoryEntity::getId, Function.identity()));
        Map<UUID, MergeRequestEntity> latestMrByRepo = mergeRequestMapper
                .selectList(Wrappers.<MergeRequestEntity>lambdaQuery().eq(MergeRequestEntity::getTaskId, taskId)
                        .in(MergeRequestEntity::getProjectRepositoryId, bindingIds)
                        .orderByDesc(MergeRequestEntity::getCreatedAt))
                .stream().collect(Collectors.toMap(MergeRequestEntity::getProjectRepositoryId, Function.identity(),
                        (first, second) -> first));
        return values.stream().map(diff -> repositoryDelivery(diff, bindingById.get(diff.getProjectRepositoryId()),
                githubById.get(bindingRepositoryId(bindingById, diff.getProjectRepositoryId())),
                latestMrByRepo.get(diff.getProjectRepositoryId()))).toList();
    }

    private RepositoryDelivery repositoryDelivery(DiffEntity diff, ProjectRepositoryEntity binding,
            GitHubRepositoryEntity github, MergeRequestEntity mr) {
        String name = binding != null && binding.getDisplayName() != null && !binding.getDisplayName().isBlank()
                ? binding.getDisplayName()
                : (github == null ? null : github.getName());
        MergeRequestBrief brief = null;
        if ("MR_CREATED".equals(diff.getDeliveryStatus()) && mr != null) {
            brief = new MergeRequestBrief(mr.getId().toString(), mr.getProviderNumber(), mr.getTitle(),
                    mr.getStatus(), webUrl(github, mr.getProviderNumber()));
        }
        return new RepositoryDelivery(diff.getProjectRepositoryId().toString(), name, diff.getId().toString(),
                diff.getDeliveryStatus(), null, diff.getDeliveryFailureReason(), brief,
                diff.getUpdatedAt() == null ? null : diff.getUpdatedAt().toInstant(ZoneOffset.UTC).toString());
    }

    /** 由 GitHub 仓库镜像与真实 PR 编号构造 MR 外部链接；不可可靠构造时返回 null。 */
    private String webUrl(GitHubRepositoryEntity github, Long providerNumber) {
        if (github == null || providerNumber == null) {
            return null;
        }
        return "https://github.com/" + github.getOwnerLogin() + "/" + github.getName() + "/pull/" + providerNumber;
    }

    private UUID bindingRepositoryId(Map<UUID, ProjectRepositoryEntity> bindingById, UUID bindingId) {
        ProjectRepositoryEntity binding = bindingById.get(bindingId);
        return binding == null ? null : binding.getRepositoryId();
    }

    private String commitMessage(TaskEntity task) {
        String title = task.getTitle() == null ? "task changes" : task.getTitle().replaceAll("[\\r\\n]+", " ").trim();
        return ("feat(task): " + title).substring(0, Math.min(500, "feat(task): ".length() + title.length()));
    }
}
