package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import qg.qgent.api.ApiException;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;
import qg.qgent.orchestration.worker.SandboxWorkerClient;
import qg.qgent.orchestration.worker.WorkerGitDiff;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies one Task-level review decision, then delivers each repository independently.
 */
@Service
public class DiffReviewBatchService {
    private static final Duration DELIVERY_LEASE = Duration.ofMinutes(30);
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

    public DiffReviewBatchService(DiffReviewBatchMapper batches, DiffMapper diffs, TaskMapper tasks,
                                  ProjectRepositoryMapper repositories, SandboxWorkerClient worker,
                                  MergeRequestService mergeRequests, ProjectAccessService access, EventService events,
                                  TransactionTemplate transactions, DiffSnapshotStorage snapshots, DiffDeliveryService deliveryService,
                                  MergeRequestMapper mergeRequestMapper, GitHubRepositoryMapper githubRepositories) {
        this.batches = batches;
        this.diffs = diffs;
        this.tasks = tasks;
        this.repositories = repositories;
        this.worker = worker;
        this.mergeRequests = mergeRequests;
        this.access = access;
        this.events = events;
        this.transactions = transactions;
        this.snapshots = snapshots;
        this.deliveryService = deliveryService;
        this.mergeRequestMapper = mergeRequestMapper;
        this.githubRepositories = githubRepositories;
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
        DiffReviewBatchEntity batch = transactions.execute(status -> claim(task));
        List<DiffEntity> values = diffs(batch.getId());
        try {
            preflight(task, values);
        } catch (RuntimeException failure) {
            restoreAfterPreflightFailure(batch.getId(), batch.getDeliveryClaimToken());
            throw failure;
        }
        transactions.execute(status -> {
            accept(task, batch.getId(), actor, batch.getDeliveryClaimToken());
            return null;
        });
        for (DiffEntity diff : diffs(batch.getId())) {
            deliver(task, diff, actor, batch.getDeliveryClaimToken());
        }
        finish(task, batch.getId(), batch.getDeliveryClaimToken());
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
            if (!"PENDING_CONFIRMATION".equals(locked.getReviewStatus())
                    || !"NOT_STARTED".equals(locked.getDeliveryStatus())) {
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
            if (!"MR_CREATED".equals(diff.getDeliveryStatus())) {
                deliver(task, diff, actor, batch.getDeliveryClaimToken());
            }
        }
        finish(task, batch.getId(), batch.getDeliveryClaimToken());
        DiffReviewBatchEntity result = requireBatch(batch.getId());
        return response(result, diffs(result.getId()));
    }

    private DiffReviewBatchEntity claim(TaskEntity task) {
        DiffReviewBatchEntity batch = latestForUpdate(task.getProjectId(), task.getId());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        boolean recoverable = "DELIVERING".equals(batch.getDeliveryStatus())
                && batch.getDeliveryLeaseExpiresAt() != null
                && !batch.getDeliveryLeaseExpiresAt().isAfter(now);
        if (!"PENDING_CONFIRMATION".equals(batch.getReviewStatus())
                || !("NOT_STARTED".equals(batch.getDeliveryStatus()) || recoverable)) {
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

    private void accept(TaskEntity task, UUID batchId, UUID actor, String claimToken) {
        DiffReviewBatchEntity batch = batches.selectByIdForUpdate(batchId);
        requireBatchClaim(batch, claimToken);
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

    private void deliver(TaskEntity task, DiffEntity diff, UUID actor, String claimToken) {
        UUID diffId = diff.getId();
        UUID batchId = diff.getReviewBatchId();
        try {
            renewBatchLease(diff.getReviewBatchId(), claimToken);
            if (!"COMMITTED".equals(diff.getDeliveryStatus()) && !"MR_CREATED".equals(diff.getDeliveryStatus())) {
                String commitSha = deliveryService.commitVerified(task, diff);
                deliveryService.recordCommitted(task, diffId, commitSha, diff.getReviewBatchId(), claimToken);
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
                    markDelivered(task, diffId, batchId, claimToken);
                    return null;
                });
            }
        } catch (RuntimeException failure) {
            markDiffFailure(task, diffId, batchId, claimToken, failure);
        }
    }


    private void markDelivered(TaskEntity task, UUID diffId, UUID batchId, String claimToken) {
        requireBatchClaim(batches.selectByIdForUpdate(batchId), claimToken);
        DiffEntity current = diffs.selectByIdForUpdate(diffId);
        if (current == null || !batchId.equals(current.getReviewBatchId())
                || !task.getId().equals(current.getTaskId()) || !task.getWorkspaceId().equals(current.getWorkspaceId())) {
            throw new ApiException(HttpStatus.CONFLICT, "DIFF_BATCH_CONTEXT_INVALID",
                    "Repository Diff no longer belongs to the claimed review batch");
        }
        current.setDeliveryStatus("MR_CREATED");
        current.setDeliveryFailureCode(null);
        current.setDeliveryFailureReason(null);
        current.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        diffs.updateById(current);
        events.publish(task.getProjectId(), task.getRequirementGroupId(), "delivery.repository.updated", diffId.toString(),
                Map.of("projectId", task.getProjectId(), "taskId", task.getId(), "diffId", diffId,
                        "deliveryStatus", "MR_CREATED"));
    }

    private void markDiffFailure(TaskEntity task, UUID diffId, UUID batchId, String claimToken,
                                 RuntimeException failure) {
        transactions.execute(status -> {
            DiffReviewBatchEntity batch = batches.selectByIdForUpdate(batchId);
            if (!ownsBatchClaim(batch, claimToken)) return null;
            DiffEntity current = diffs.selectByIdForUpdate(diffId);
            current.setDeliveryStatus("FAILED");
            current.setDeliveryFailureCode(failureCode(failure));
            current.setDeliveryFailureReason("Repository delivery failed (" + failureCode(failure) + ")");
            current.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            diffs.updateById(current);
            return null;
        });
    }

    private void finish(TaskEntity task, UUID batchId, String claimToken) {
        transactions.execute(status -> {
            DiffReviewBatchEntity batch = batches.selectByIdForUpdate(batchId);
            requireBatchClaim(batch, claimToken);
            List<DiffEntity> values = diffs(batchId);
            boolean allDelivered = values.stream().allMatch(value -> "MR_CREATED".equals(value.getDeliveryStatus()));
            boolean anyDelivered = values.stream().anyMatch(value -> "MR_CREATED".equals(value.getDeliveryStatus()));
            batch.setDeliveryStatus(allDelivered ? "DELIVERED" : anyDelivered ? "PARTIALLY_DELIVERED" : "FAILED");
            batch.setDeliveryClaimToken(null);
            batch.setDeliveryLeaseExpiresAt(null);
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
                batch.getDeliveryStatus(), batch.getAggregateHash(), batch.getReviewReason(), items,
                repositoryDeliveries(batch, values));
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
                .eq(MergeRequestEntity::getTaskId, batch.getTaskId())
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
