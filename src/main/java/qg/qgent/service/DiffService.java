package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.*;
import qg.qgent.entity.DiffCommentEntity;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.DiffFileEntity;
import qg.qgent.entity.DiffReviewBatchEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads and reviews immutable working-tree Diff snapshots produced by
 * controlled execution.
 */
@Service
public class DiffService {
    /** 群聊卡片单文件预览的最大行数。 */
    private static final int PREVIEW_LINE_LIMIT = 200;
    /** 群聊卡片文件标签的最大数量，避免单条消息展开造成超大响应。 */
    private static final int PREVIEW_FILE_LIMIT = 100;
    /** 单条代码行可返回的最大 Unicode 字符数。 */
    private static final int PREVIEW_LINE_CONTENT_LIMIT = 4_000;
    private static final Set<String> PREVIEW_LINE_TYPES = Set.of("CONTEXT", "DELETE", "ADD");

    private final DiffMapper diffs;
    private final DiffFileMapper files;
    private final DiffCommentMapper comments;
    private final DiffReviewBatchMapper batches;
    private final TaskMapper tasks;
    private final TaskRunMapper taskRuns;
    private final TaskStepMapper taskSteps;
    private final WorkspaceMapper workspaces;
    private final ProjectAccessService access;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final DiffDeliveryService deliveryService;
    private final UserMapper users;

    public DiffService(DiffMapper diffs, DiffFileMapper files, DiffCommentMapper comments,
                       DiffReviewBatchMapper batches, TaskMapper tasks, TaskRunMapper taskRuns,
                       TaskStepMapper taskSteps,
                       WorkspaceMapper workspaces, ProjectAccessService access, EventService eventService,
                       NotificationService notificationService, DiffDeliveryService deliveryService,
                       UserMapper users) {
        this.diffs = diffs;
        this.files = files;
        this.comments = comments;
        this.batches = batches;
        this.tasks = tasks;
        this.taskRuns = taskRuns;
        this.taskSteps = taskSteps;
        this.workspaces = workspaces;
        this.access = access;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.deliveryService = deliveryService;
        this.users = users;
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

    /**
     * 返回群聊卡片使用的最终 Diff 轻量预览。
     *
     * <p>普通 Diff、TaskRun 过程 Diff 和未关联任务级 DiffReviewBatch 的 Diff 一律拒绝，避免群聊
     * 将内部执行产物误展示成用户最终交付。每个文件最多 200 条结构化行，文件标签同样最多 100 个；
     * 超限时只返回截断标记和完整详情页路径。</p>
     */
    public FinalDiffPreviewResponse finalPreview(UUID projectId, UUID diffId, UUID selectedFileId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        DiffEntity diff = requireDiff(projectId, diffId);
        requireFinalTaskDiff(projectId, diff);

        long totalFileCount = files.selectCount(Wrappers.<DiffFileEntity>lambdaQuery()
                .eq(DiffFileEntity::getDiffId, diff.getId()));
        List<DiffFileEntity> rows = files.selectPreviewFileSummaries(diff.getId(), PREVIEW_FILE_LIMIT + 1);
        boolean filesTruncated = rows.size() > PREVIEW_FILE_LIMIT;
        List<DiffFileEntity> visibleFiles = filesTruncated ? rows.subList(0, PREVIEW_FILE_LIMIT) : rows;

        DiffFileEntity selected = selectedPreviewFile(diff.getId(), selectedFileId, visibleFiles, filesTruncated);
        PreviewLines preview = selected == null ? PreviewLines.empty() : previewLines(selected);
        List<DiffPreviewFileResponse> summaries = visibleFiles.stream().map(this::previewFile).toList();

        return new FinalDiffPreviewResponse(diff.getId().toString(),
                "/app/projects/" + projectId + "/code/diff/" + diff.getId(), PREVIEW_LINE_LIMIT, totalFileCount,
                filesTruncated, summaries, selected == null ? null : selected.getId().toString(), preview.lineCount(),
                preview.lines(), preview.truncated(),
                filesTruncated || preview.truncated() || preview.contentTruncated());
    }

    /**
     * Lists project Diffs, optionally scoped to one task, ordered newest first.
     */
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
        List<DiffCommentEntity> rows = comments.selectList(
                Wrappers.<DiffCommentEntity>lambdaQuery().eq(DiffCommentEntity::getDiffId, diffId)
                        .orderByAsc(DiffCommentEntity::getCreatedAt));
        Set<UUID> authorIds = rows.stream().map(DiffCommentEntity::getAuthorUserId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, UserEntity> userById = authorIds.isEmpty() ? Map.of() : users
                .selectBatchIds(authorIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, java.util.function.Function.identity()));
        return rows.stream().map(c -> response(c, userName(userById.get(c.getAuthorUserId())))).toList();
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
        UserEntity author = users.selectById(actor);
        return response(value, userName(author));
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

    /** 校验 Diff 是当前项目、任务和最终审核批次一致的最终快照。 */
    private void requireFinalTaskDiff(UUID projectId, DiffEntity diff) {
        if (diff.getReviewBatchId() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DIFF_PREVIEW_FINAL_ONLY",
                    "群聊仅支持展开任务最终 Diff");
        }
        DiffReviewBatchEntity batch = batches.selectById(diff.getReviewBatchId());
        TaskEntity task = tasks.selectById(diff.getTaskId());
        TaskRunEntity finalRun = batch == null || batch.getFinalCodingTaskRunId() == null ? null
                : taskRuns.selectById(batch.getFinalCodingTaskRunId());
        TaskStepEntity finalStep = finalRun == null || finalRun.getTaskStepId() == null ? null
                : taskSteps.selectById(finalRun.getTaskStepId());
        if (batch == null || task == null || !projectId.equals(batch.getProjectId())
                || !projectId.equals(task.getProjectId()) || !Objects.equals(diff.getTaskId(), batch.getTaskId())
                || !Objects.equals(diff.getTaskId(), task.getId())
                || !Objects.equals(diff.getWorkspaceId(), batch.getWorkspaceId())
                || !Objects.equals(diff.getWorkspaceId(), task.getWorkspaceId())
                || !Objects.equals(diff.getTaskRunId(), batch.getFinalCodingTaskRunId())
                || finalRun == null || !projectId.equals(finalRun.getProjectId())
                || !Objects.equals(finalRun.getTaskId(), task.getId())
                || !"DEVELOPER".equals(finalRun.getRole()) || !"SUCCEEDED".equals(finalRun.getStatus())
                || finalStep == null || !Objects.equals(finalStep.getTaskId(), task.getId())
                || !"DEVELOPER".equals(finalStep.getRole())
                || !Objects.equals(finalStep.getId(), finalRun.getTaskStepId())
                || !Objects.equals(diff.getTaskStepId(), finalStep.getId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DIFF_PREVIEW_CONTEXT_INVALID",
                    "最终 Diff 上下文不一致");
        }
    }

    /** 选择卡片当前文件；显式指定的文件必须属于同一个 Diff。 */
    private DiffFileEntity selectedPreviewFile(UUID diffId, UUID selectedFileId, List<DiffFileEntity> visibleFiles,
                                               boolean filesTruncated) {
        UUID resolvedFileId = selectedFileId;
        if (resolvedFileId == null) {
            resolvedFileId = visibleFiles.isEmpty() ? null : visibleFiles.getFirst().getId();
            if (resolvedFileId == null) {
                return null;
            }
        }
        DiffFileEntity selected = files.selectById(resolvedFileId);
        if (selected == null || !diffId.equals(selected.getDiffId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DIFF_FILE_NOT_FOUND", "Diff 文件不存在或不属于当前 Diff");
        }
        UUID checkedFileId = resolvedFileId;
        if (filesTruncated && visibleFiles.stream().noneMatch(file -> checkedFileId.equals(file.getId()))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DIFF_PREVIEW_FILE_LIMIT",
                    "群聊 Diff 卡仅可预览前 100 个文件，请查看详情");
        }
        return selected;
    }

    private DiffPreviewFileResponse previewFile(DiffFileEntity file) {
        String fileName = fileName(file.getPath());
        return new DiffPreviewFileResponse(id(file.getId()), file.getSequenceNo(), file.getPath(), fileName,
                extension(fileName), file.getChangeType(), file.getAdditions(), file.getDeletions(),
                file.getBinaryFlag());
    }

    /**
     * 将 Worker 持久化的 hunk JSON 容错转换为群聊所需的扁平结构化行。
     * 仅检查第 201 条有效行，以严格限制 CPU、内存和响应体积。
     */
    private PreviewLines previewLines(DiffFileEntity file) {
        if (Boolean.TRUE.equals(file.getBinaryFlag()) || file.getHunks() == null || file.getHunks().isEmpty()) {
            return PreviewLines.empty();
        }
        List<DiffPreviewLineResponse> result = new ArrayList<>();
        int lineCount = 0;
        boolean contentTruncated = false;
        for (Object hunk : file.getHunks()) {
            if (!(hunk instanceof Map<?, ?> hunkMap) || !(hunkMap.get("lines") instanceof Collection<?> rows)) {
                continue;
            }
            for (Object row : rows) {
                if (!(row instanceof Map<?, ?> values)) {
                    continue;
                }
                String type = text(values.get("type"));
                if (type == null || !PREVIEW_LINE_TYPES.contains(type)) {
                    continue;
                }
                lineCount++;
                if (lineCount > PREVIEW_LINE_LIMIT) {
                    return new PreviewLines(lineCount, result, true, contentTruncated);
                }
                LineContent content = previewContent(values.get("content"));
                contentTruncated |= content.truncated();
                result.add(new DiffPreviewLineResponse(type, number(values.get("oldLineNo")),
                        number(values.get("newLineNo")), content.value(), content.truncated()));
            }
        }
        return new PreviewLines(lineCount, result, false, contentTruncated);
    }

    private String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "未命名文件";
        }
        String normalized = path.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String name = slash < 0 ? normalized : normalized.substring(slash + 1);
        return name.isBlank() ? normalized : name;
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && dot < fileName.length() - 1 ? fileName.substring(dot + 1) : null;
    }

    private String text(Object value) {
        return value instanceof String string ? string : null;
    }

    private LineContent previewContent(Object value) {
        String content = text(value);
        if (content == null || content.isEmpty()) {
            return new LineContent("", false);
        }
        int codePoints = content.codePointCount(0, content.length());
        if (codePoints <= PREVIEW_LINE_CONTENT_LIMIT) {
            return new LineContent(content, false);
        }
        int end = content.offsetByCodePoints(0, PREVIEW_LINE_CONTENT_LIMIT);
        return new LineContent(content.substring(0, end), true);
    }

    private Integer number(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private record PreviewLines(int lineCount, List<DiffPreviewLineResponse> lines, boolean truncated,
                                boolean contentTruncated) {
        private static PreviewLines empty() {
            return new PreviewLines(0, List.of(), false, false);
        }
    }

    private record LineContent(String value, boolean truncated) {
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

    /**
     * 批量加载 Diff 所属任务的需求群ID，避免列表逐行查询。
     */
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

    private DiffCommentResponse response(DiffCommentEntity c, String authorName) {
        return new DiffCommentResponse(id(c.getId()),
                id(c.getDiffId()), c.getPath(), c.getSide(), c.getLine(), c.getHunkId(),
                c.getCommitSha(), c.getBody(),
                id(c.getAuthorUserId()), authorName, iso(c.getCreatedAt()));
    }

    private String userName(UserEntity user) {
        return user == null ? null : user.getDisplayName();
    }

    private String id(UUID v) {
        return v == null ? null : v.toString();
    }

    private String iso(LocalDateTime v) {
        return v == null ? null : v.toInstant(ZoneOffset.UTC).toString();
    }
}
