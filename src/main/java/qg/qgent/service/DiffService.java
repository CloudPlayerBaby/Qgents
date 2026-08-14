package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads and reviews immutable working-tree Diff snapshots produced by
 * controlled execution.
 */
@Service
public class DiffService {
        private final DiffMapper diffs;
        private final DiffFileMapper files;
        private final DiffCommentMapper comments;
        private final TaskMapper tasks;
        private final WorkspaceMapper workspaces;
        private final ProjectAccessService access;
        private final EventService eventService;
        private final NotificationService notificationService;
        private final DiffDeliveryService deliveryService;

        public DiffService(DiffMapper diffs, DiffFileMapper files, DiffCommentMapper comments, TaskMapper tasks,
                        WorkspaceMapper workspaces, ProjectAccessService access, EventService eventService,
                        NotificationService notificationService, DiffDeliveryService deliveryService) {
                this.diffs = diffs;
                this.files = files;
                this.comments = comments;
                this.tasks = tasks;
                this.workspaces = workspaces;
                this.access = access;
                this.eventService = eventService;
                this.notificationService = notificationService;
                this.deliveryService = deliveryService;
        }

        /**
         * Diff 产出后向任务发起人写入"有交付物待处理"通知（A 联调约定 §1）。
         * 任务不存在或发起人缺失时静默跳过，不阻断 Diff 创建。
         */
        private void notifyDeliverablePending(DiffEntity diff) {
                if (diff.getTaskId() == null) {
                        return;
                }
                TaskEntity task = tasks.selectById(diff.getTaskId());
                if (task == null) {
                        return;
                }
                notificationService.notify(task.getCreatedBy(), diff.getProjectId(), task.getRequirementGroupId(),
                                "DELIVERABLE_PENDING", "待审阅 Diff：" + task.getTitle(),
                                "任务已产出待审阅的代码变更", diff.getId().toString());
        }

        /**
         * 受控执行接缝：持久化不可变工作树 Diff 快照并发布 diff.created 事件。
         * 调用方（受控执行服务）必须已完成项目归属与工作树校验，baseCommit/sourceBranch
         * 必须来自真实 Git，禁止伪造。taskRunId/taskStepId 记录产出该 Diff 的运行与步骤。
         */
        @Transactional
        public DiffResponse create(UUID projectId, UUID taskId, UUID taskRunId, UUID taskStepId, UUID repositoryId,
                        UUID workspaceId, String baseCommit, String sourceBranch, String workingTreeHash,
                        Map<String, Object> changeStats) {
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                DiffEntity diff = new DiffEntity();
                diff.setId(UuidV7.next());
                diff.setProjectId(projectId);
                diff.setTaskId(taskId);
                diff.setTaskRunId(taskRunId);
                diff.setTaskStepId(taskStepId);
                diff.setWorkspaceId(workspaceId);
                diff.setProjectRepositoryId(repositoryId);
                diff.setBaseCommit(baseCommit);
                diff.setSourceBranch(sourceBranch);
                diff.setWorkingTreeHash(workingTreeHash);
                diff.setStatus("PENDING_REVIEW");
                diff.setChangeStats(changeStats);
                diff.setCreatedAt(now);
                diff.setUpdatedAt(now);
                diffs.insert(diff);
                eventService.publish(projectId, null, "diff.created", diff.getId().toString(),
                                TaskEventPayloads.diffCreated(diff));
                notifyDeliverablePending(diff);
                return detail(diff);
        }

        public DiffResponse get(UUID projectId, UUID diffId, UUID actor) {
                access.requireProjectMember(projectId, actor);
                return detail(requireDiff(projectId, diffId));
        }

        /** Lists project Diffs, optionally scoped to one task, ordered newest first. */
        public ApiPageResponse<DiffListItemResponse> list(UUID projectId, UUID taskId, UUID actor, String cursor,
                        int limit, String requestId) {
                access.requireProjectMember(projectId, actor);
                int size = Math.min(limit <= 0 ? 20 : limit, 100);
                UUID cursorId = parseCursor(cursor);
                List<DiffEntity> rows = diffs.selectList(Wrappers.<DiffEntity>lambdaQuery()
                                .eq(DiffEntity::getProjectId, projectId)
                                .eq(taskId != null, DiffEntity::getTaskId, taskId)
                                .lt(cursorId != null, DiffEntity::getId, cursorId)
                                .orderByDesc(DiffEntity::getId).last("LIMIT " + (size + 1)));
                boolean more = rows.size() > size;
                List<DiffEntity> pageRows = more ? rows.subList(0, size) : rows;
                Map<UUID, String> groupByTask = requirementGroups(pageRows);
                List<DiffListItemResponse> items = pageRows.stream().map(d -> listItem(d,
                                groupByTask.get(d.getTaskId()))).toList();
                return new ApiPageResponse<>(items,
                                new PageMeta(more ? items.getLast().getId() : null, more), requestId);
        }

        public ApiPageResponse<DiffFileResponse> files(UUID projectId, UUID diffId, UUID actor, String cursor,
                        int limit,
                        String requestId) {
                access.requireProjectMember(projectId, actor);
                requireDiff(projectId, diffId);
                int size = Math.min(limit <= 0 ? 20 : limit, 100);
                long after = cursor == null ? 0 : Long.parseLong(cursor);
                List<DiffFileEntity> rows = files.selectList(Wrappers.<DiffFileEntity>lambdaQuery()
                                .eq(DiffFileEntity::getDiffId, diffId).gt(DiffFileEntity::getSequenceNo, after)
                                .orderByAsc(DiffFileEntity::getSequenceNo).last("LIMIT " + (size + 1)));
                boolean more = rows.size() > size;
                List<DiffFileResponse> items = (more ? rows.subList(0, size) : rows).stream().map(this::response)
                                .toList();
                return new ApiPageResponse<>(items,
                                new PageMeta(more ? String.valueOf(items.getLast().getSequence()) : null, more),
                                requestId);
        }

        public List<DiffCommentResponse> comments(UUID projectId, UUID diffId, UUID actor) {
                access.requireProjectMember(projectId, actor);
                requireDiff(projectId, diffId);
                return comments.selectList(
                                Wrappers.<DiffCommentEntity>lambdaQuery().eq(DiffCommentEntity::getDiffId, diffId)
                                                .orderByAsc(DiffCommentEntity::getCreatedAt))
                                .stream().map(this::response).toList();
        }

        @Transactional
        public DiffCommentResponse addComment(UUID projectId, UUID diffId, UUID actor, DiffCommentRequest body) {
                access.requireProjectMember(projectId, actor);
                DiffEntity diff = requireDiff(projectId, diffId);
                DiffCommentEntity value = new DiffCommentEntity();
                value.setId(UuidV7.next());
                value.setDiffId(diffId);
                value.setPath(body.getPath());
                value.setSide(body.getSide());
                value.setLine(body.getLine());
                value.setHunkId(body.getHunkId());
                value.setCommitSha(diff.getHeadCommit());
                value.setBody(body.getBody());
                value.setAuthorUserId(actor);
                value.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
                comments.insert(value);
                return response(value);
        }

        public DiffResponse decide(UUID projectId, UUID diffId, UUID actor, boolean accepted, String reason) {
                DiffEntity diff = requireDiff(projectId, diffId);
                TaskEntity task = tasks.selectById(diff.getTaskId());
                if (task == null || !projectId.equals(task.getProjectId())
                                || !diff.getWorkspaceId().equals(task.getWorkspaceId())) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DIFF_TASK_CONTEXT_INVALID",
                                        "Diff, Task and Workspace ownership is inconsistent");
                }
                if (!actor.equals(task.getCreatedBy()))
                        access.requireProjectAdmin(projectId, actor);
                if (!"PENDING_REVIEW".equals(diff.getStatus()))
                        throw new ApiException(HttpStatus.CONFLICT,
                                        "DIFF_NOT_DECIDABLE", "Only a pending Diff may be reviewed");
                if (diff.getReviewBatchId() != null) {
                        throw new ApiException(HttpStatus.CONFLICT, "DIFF_BATCH_REVIEW_REQUIRED",
                                        "This Diff belongs to a Task-level final Diff review");
                }
                if (!accepted && (reason == null || reason.isBlank()))
                        throw new ApiException(HttpStatus.BAD_REQUEST,
                                        "DIFF_REJECT_REASON_REQUIRED", "A rejection reason is required");
                if (accepted) {
                        return detail(deliveryService.acceptNonBatch(task, diff, actor));
                }
                return detail(deliveryService.rejectNonBatch(task, diff, actor, reason));
        }

        private DiffEntity requireDiff(UUID projectId, UUID id) {
                DiffEntity value = diffs.selectById(id);
                if (value == null || !projectId.equals(value.getProjectId()))
                        throw new ApiException(HttpStatus.NOT_FOUND,
                                        "DIFF_NOT_FOUND", "Diff does not exist or is not visible");
                return value;
        }

        private DiffResponse detail(DiffEntity d) {
                TaskEntity task = tasks.selectById(d.getTaskId());
                String groupId = task == null ? null : id(task.getRequirementGroupId());
                return new DiffResponse(id(d.getId()), id(d.getProjectId()), id(d.getTaskId()), id(d.getTaskRunId()),
                                id(d.getTaskStepId()), groupId, id(d.getWorkspaceId()),
                                id(d.getProjectRepositoryId()), d.getBaseCommit(), d.getSourceBranch(),
                                d.getWorkingTreeHash(), d.getSnapshotKey(), d.getHeadCommit(), d.getStatus(),
                                id(d.getReviewedBy()), d.getReviewReason(), iso(d.getReviewedAt()), d.getChangeStats(),
                                iso(d.getCreatedAt()), iso(d.getUpdatedAt()));
        }

        private DiffListItemResponse listItem(DiffEntity d, String groupId) {
                return new DiffListItemResponse(id(d.getId()), id(d.getProjectId()), id(d.getTaskId()),
                                id(d.getTaskRunId()), id(d.getTaskStepId()), groupId, id(d.getWorkspaceId()),
                                id(d.getProjectRepositoryId()), d.getBaseCommit(), d.getSourceBranch(),
                                d.getHeadCommit(), d.getStatus(), d.getChangeStats(), iso(d.getCreatedAt()));
        }

        /** 批量加载 Diff 所属任务的需求群ID，避免列表逐行查询。 */
        private Map<UUID, String> requirementGroups(List<DiffEntity> rows) {
                Set<UUID> taskIds = rows.stream().map(DiffEntity::getTaskId).collect(Collectors.toSet());
                if (taskIds.isEmpty()) {
                        return Map.of();
                }
                return tasks.selectBatchIds(taskIds).stream()
                                .collect(Collectors.toMap(TaskEntity::getId, t -> id(t.getRequirementGroupId())));
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

        private DiffFileResponse response(DiffFileEntity f) {
                return new DiffFileResponse(id(f.getId()), f.getSequenceNo(), f.getPath(),
                                f.getChangeType(), f.getAdditions(), f.getDeletions(), f.getBinaryFlag(), f.getHunks());
        }

        private DiffCommentResponse response(DiffCommentEntity c) {
                return new DiffCommentResponse(id(c.getId()),
                                id(c.getDiffId()), c.getPath(), c.getSide(), c.getLine(), c.getHunkId(),
                                c.getCommitSha(), c.getBody(),
                                id(c.getAuthorUserId()), iso(c.getCreatedAt()));
        }

        private String id(UUID v) {
                return v == null ? null : v.toString();
        }

        private String iso(LocalDateTime v) {
                return v == null ? null : v.toInstant(ZoneOffset.UTC).toString();
        }
}
