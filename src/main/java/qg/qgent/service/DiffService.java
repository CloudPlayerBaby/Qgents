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
import java.util.UUID;

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

        public DiffService(DiffMapper diffs, DiffFileMapper files, DiffCommentMapper comments, TaskMapper tasks,
                        WorkspaceMapper workspaces, ProjectAccessService access) {
                this.diffs = diffs;
                this.files = files;
                this.comments = comments;
                this.tasks = tasks;
                this.workspaces = workspaces;
                this.access = access;
        }

        public DiffResponse get(UUID projectId, UUID diffId, UUID actor) {
                access.requireProjectMember(projectId, actor);
                return response(requireDiff(projectId, diffId));
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

        @Transactional
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
                if (!accepted && (reason == null || reason.isBlank()))
                        throw new ApiException(HttpStatus.BAD_REQUEST,
                                        "DIFF_REJECT_REASON_REQUIRED", "A rejection reason is required");
                WorkspaceEntity workspace = workspaces.selectByIdForUpdate(diff.getWorkspaceId());
                if (workspace == null || !projectId.equals(workspace.getProjectId())) {
                        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DIFF_WORKSPACE_CONTEXT_INVALID",
                                        "Diff Workspace does not belong to the current Project");
                }
                diff.setStatus(accepted ? "ACCEPTED" : "REJECTED");
                diff.setReviewedBy(actor);
                diff.setReviewReason(reason);
                diff.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
                diff.setUpdatedAt(diff.getReviewedAt());
                diffs.updateById(diff);
                return response(diff);
        }

        private DiffEntity requireDiff(UUID projectId, UUID id) {
                DiffEntity value = diffs.selectById(id);
                if (value == null || !projectId.equals(value.getProjectId()))
                        throw new ApiException(HttpStatus.NOT_FOUND,
                                        "DIFF_NOT_FOUND", "Diff does not exist or is not visible");
                return value;
        }

        private DiffResponse response(DiffEntity d) {
                return new DiffResponse(id(d.getId()), id(d.getProjectId()),
                                id(d.getTaskId()), id(d.getWorkspaceId()), id(d.getProjectRepositoryId()),
                                d.getBaseCommit(),
                                d.getSourceBranch(), d.getWorkingTreeHash(), d.getSnapshotKey(), d.getHeadCommit(),
                                d.getStatus(),
                                id(d.getReviewedBy()), d.getReviewReason(), iso(d.getReviewedAt()), d.getChangeStats(),
                                iso(d.getCreatedAt()),
                                iso(d.getUpdatedAt()));
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
