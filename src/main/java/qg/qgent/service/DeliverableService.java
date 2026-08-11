package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.DeliverableResponse;
import qg.qgent.dto.DiffCommentResponse;
import qg.qgent.dto.DiffFileResponse;
import qg.qgent.dto.DiffResponse;
import qg.qgent.dto.PageMeta;
import qg.qgent.entity.DeliverableEntity;
import qg.qgent.entity.DiffCommentEntity;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.DiffFileEntity;
import qg.qgent.mapper.DeliverableMapper;
import qg.qgent.mapper.DiffCommentMapper;
import qg.qgent.mapper.DiffFileMapper;
import qg.qgent.mapper.DiffMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 交付物、Diff 与审查意见服务。
 * 交付物由受控执行服务产出，客户端不得伪造关联的提交、测试结果或 Diff；
 * accept 仅表示业务方接受，不绕过质量门禁、也不等同于合并。
 * accept/reject 为同步决策，只允许 PENDING_REVIEW 状态；Diff 评论绑定当前 Diff 头提交，
 * 避免 Diff 更新后评论指向错误代码。
 */
@Service
public class DeliverableService {
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final DeliverableMapper deliverableMapper;
    private final DiffMapper diffMapper;
    private final DiffFileMapper diffFileMapper;
    private final DiffCommentMapper diffCommentMapper;
    private final ProjectAccessService projectAccess;
    private final EventService eventService;

    public DeliverableService(DeliverableMapper deliverableMapper, DiffMapper diffMapper,
            DiffFileMapper diffFileMapper, DiffCommentMapper diffCommentMapper, ProjectAccessService projectAccess,
            EventService eventService) {
        this.deliverableMapper = deliverableMapper;
        this.diffMapper = diffMapper;
        this.diffFileMapper = diffFileMapper;
        this.diffCommentMapper = diffCommentMapper;
        this.projectAccess = projectAccess;
        this.eventService = eventService;
    }

    /**
     * 查询工作包产出的交付物（游标分页，按交付物 ID 倒序）。
     */
    public ApiPageResponse<DeliverableResponse> listByWorkPackage(UUID projectId, UUID workPackageId, UUID userId,
            String cursor, int limit, String requestId) {
        projectAccess.requireProjectMember(projectId, userId);
        int size = clampLimit(limit);
        UUID cursorUuid = parseCursor(cursor);
        List<DeliverableEntity> rows = deliverableMapper.selectList(Wrappers.<DeliverableEntity>lambdaQuery()
                .eq(DeliverableEntity::getProjectId, projectId)
                .eq(DeliverableEntity::getWorkPackageId, workPackageId)
                .lt(cursorUuid != null, DeliverableEntity::getId, cursorUuid)
                .orderByDesc(DeliverableEntity::getId)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<DeliverableResponse> items = (hasMore ? rows.subList(0, size) : rows).stream()
                .map(this::toResponse)
                .toList();
        PageMeta page = new PageMeta(hasMore ? items.get(items.size() - 1).getId() : null, hasMore);
        return new ApiPageResponse<>(items, page, requestId);
    }

    /**
     * 获取交付物详情：关联运行、分支与检查摘要。
     */
    public DeliverableResponse detail(UUID projectId, UUID deliverableId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        return toResponse(requireDeliverable(projectId, deliverableId));
    }

    /**
     * 接受 PENDING_REVIEW 交付物（发起人或 Project Admin）。
     * 只代表业务方接受，不绕过目标分支质量门禁，也不等同合并。
     */
    @Transactional
    public DeliverableResponse accept(UUID projectId, UUID deliverableId, UUID userId, String reason) {
        projectAccess.requireProjectMember(projectId, userId);
        DeliverableEntity d = requireDeliverable(projectId, deliverableId);
        requireOwner(d, projectId, userId);
        requirePending(d);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        d.setStatus("ACCEPTED");
        d.setReviewedBy(userId);
        d.setReviewReason(reason);
        d.setReviewedAt(now);
        d.setUpdatedAt(now);
        deliverableMapper.updateById(d);
        publishUpdated(d);
        return toResponse(d);
    }

    /**
     * 拒绝 PENDING_REVIEW 交付物并给出退回原因（发起人或 Project Admin，拒绝原因必填）。
     */
    @Transactional
    public DeliverableResponse reject(UUID projectId, UUID deliverableId, UUID userId, String reason) {
        projectAccess.requireProjectMember(projectId, userId);
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DELIVERABLE_REJECT_REASON_REQUIRED", "拒绝交付物必须给出退回原因");
        }
        DeliverableEntity d = requireDeliverable(projectId, deliverableId);
        requireOwner(d, projectId, userId);
        requirePending(d);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        d.setStatus("REJECTED");
        d.setReviewedBy(userId);
        d.setReviewReason(reason);
        d.setReviewedAt(now);
        d.setUpdatedAt(now);
        deliverableMapper.updateById(d);
        publishUpdated(d);
        return toResponse(d);
    }

    /**
     * 查询 Diff 的变更统计和关联提交。
     */
    public DiffResponse diff(UUID projectId, UUID diffId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        return toResponse(requireDiff(projectId, diffId));
    }

    /**
     * 游标读取 Diff 文件、hunk 与二进制文件摘要。
     */
    public ApiPageResponse<DiffFileResponse> diffFiles(UUID projectId, UUID diffId, UUID userId, String cursor,
            int limit, String requestId) {
        projectAccess.requireProjectMember(projectId, userId);
        requireDiff(projectId, diffId);
        int size = clampLimit(limit);
        long after = parseLongCursor(cursor);
        List<DiffFileEntity> rows = diffFileMapper.selectList(Wrappers.<DiffFileEntity>lambdaQuery()
                .eq(DiffFileEntity::getDiffId, diffId)
                .gt(DiffFileEntity::getSequenceNo, after)
                .orderByAsc(DiffFileEntity::getSequenceNo)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<DiffFileResponse> items = (hasMore ? rows.subList(0, size) : rows).stream().map(this::toResponse).toList();
        PageMeta page = new PageMeta(hasMore ? String.valueOf(items.get(items.size() - 1).getSequence()) : null, hasMore);
        return new ApiPageResponse<>(items, page, requestId);
    }

    /**
     * 查询 Diff 审查意见列表。
     */
    public List<DiffCommentResponse> diffComments(UUID projectId, UUID diffId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        requireDiff(projectId, diffId);
        return diffCommentMapper.selectList(Wrappers.<DiffCommentEntity>lambdaQuery()
                .eq(DiffCommentEntity::getDiffId, diffId).orderByAsc(DiffCommentEntity::getCreatedAt))
                .stream().map(this::toResponse).toList();
    }

    /**
     * 添加一条 Diff 审查意见，绑定当前 Diff 头提交 SHA。
     */
    @Transactional
    public DiffCommentResponse addDiffComment(UUID projectId, UUID diffId, UUID userId, String path, String side,
            Integer line, String hunkId, String body) {
        projectAccess.requireProjectMember(projectId, userId);
        DiffEntity diff = requireDiff(projectId, diffId);
        if (body == null || body.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "DIFF_COMMENT_BODY_REQUIRED", "审查意见正文不能为空");
        }
        DiffCommentEntity c = new DiffCommentEntity();
        c.setId(UuidV7.next());
        c.setDiffId(diffId);
        c.setPath(path);
        c.setSide(side);
        c.setLine(line);
        c.setHunkId(hunkId);
        c.setCommitSha(diff.getHeadCommit());
        c.setBody(body);
        c.setAuthorUserId(userId);
        c.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        diffCommentMapper.insert(c);
        return toResponse(c);
    }

    /**
     * 受控执行服务接缝：根据执行结果创建交付物并发布 deliverable.created 事件。
     * 无客户端创建端点，供未来执行器集成调用；提交、测试结果与 Diff 必须由执行服务真实产出。
     *
     * @param projectId     所属项目ID
     * @param taskRunId     产出交付物的任务运行ID
     * @param workPackageId 所属工作包ID
     * @param groupId       可选需求群ID
     * @param repositoryId  项目仓库绑定ID
     * @param sourceBranch  交付变更所在源分支
     * @param headCommit    交付提交SHA
     * @param summary       交付摘要 JSON
     * @param createdBy     发起用户ID
     */
    @Transactional
    public DeliverableResponse createFromExecution(UUID projectId, UUID taskRunId, UUID workPackageId, UUID groupId,
            UUID repositoryId, String sourceBranch, String headCommit, Map<String, Object> summary, UUID createdBy) {
        // TODO 接缝：由受控执行服务在真实完成提交与检查后调用；本方法仅持久化交付物并发布事件
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        DeliverableEntity d = new DeliverableEntity();
        d.setId(UuidV7.next());
        d.setProjectId(projectId);
        d.setRequirementGroupId(groupId);
        d.setWorkPackageId(workPackageId);
        d.setTaskRunId(taskRunId);
        d.setProjectRepositoryId(repositoryId);
        d.setSourceBranch(sourceBranch);
        d.setHeadCommit(headCommit);
        d.setSummary(summary);
        d.setStatus("PENDING_REVIEW");
        d.setCreatedBy(createdBy);
        d.setCreatedAt(now);
        d.setUpdatedAt(now);
        deliverableMapper.insert(d);
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", projectId);
        if (groupId != null) {
            payload.put("groupId", groupId);
        }
        payload.put("workPackageId", workPackageId);
        payload.put("taskRunId", taskRunId);
        payload.put("deliverableId", d.getId());
        payload.put("sourceBranch", sourceBranch);
        payload.put("headCommit", headCommit);
        payload.put("status", d.getStatus());
        payload.put("sequence", 0);
        payload.put("timestamp", Instant.now().toString());
        eventService.publish(projectId, groupId, "deliverable.created", d.getId().toString(), payload);
        return toResponse(d);
    }

    // ---------- 私有辅助 ----------

    private DeliverableEntity requireDeliverable(UUID projectId, UUID deliverableId) {
        DeliverableEntity d = deliverableMapper.selectById(deliverableId);
        if (d == null || !d.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DELIVERABLE_NOT_FOUND", "交付物不存在或不可见");
        }
        return d;
    }

    private DiffEntity requireDiff(UUID projectId, UUID diffId) {
        DiffEntity diff = diffMapper.selectById(diffId);
        if (diff == null || !diff.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DIFF_NOT_FOUND", "Diff 不存在或不可见");
        }
        return diff;
    }

    private void requireOwner(DeliverableEntity d, UUID projectId, UUID userId) {
        if (!projectAccess.isOwnerOrAdmin(d.getCreatedBy(), projectId, userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DELIVERABLE_FORBIDDEN", "仅发起人或 Project Admin 可处理该交付物");
        }
    }

    private void requirePending(DeliverableEntity d) {
        if (!"PENDING_REVIEW".equals(d.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "DELIVERABLE_NOT_DECIDABLE", "仅 PENDING_REVIEW 状态的交付物可接受或拒绝");
        }
    }

    private void publishUpdated(DeliverableEntity d) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", d.getProjectId());
        if (d.getRequirementGroupId() != null) {
            payload.put("groupId", d.getRequirementGroupId());
        }
        payload.put("workPackageId", d.getWorkPackageId());
        payload.put("taskRunId", d.getTaskRunId());
        payload.put("deliverableId", d.getId());
        payload.put("status", d.getStatus());
        payload.put("sequence", 0);
        payload.put("timestamp", Instant.now().toString());
        eventService.publish(d.getProjectId(), d.getRequirementGroupId(), "deliverable.updated", d.getId().toString(),
                payload);
    }

    private DeliverableResponse toResponse(DeliverableEntity d) {
        return new DeliverableResponse(id(d.getId()), id(d.getProjectId()), id(d.getRequirementGroupId()),
                id(d.getWorkPackageId()), id(d.getTaskRunId()), id(d.getProjectRepositoryId()), d.getSourceBranch(),
                d.getHeadCommit(), d.getSummary(), d.getStatus(), id(d.getCreatedBy()), id(d.getReviewedBy()),
                d.getReviewReason(), iso(d.getReviewedAt()), iso(d.getCreatedAt()));
    }

    private DiffResponse toResponse(DiffEntity diff) {
        return new DiffResponse(id(diff.getId()), id(diff.getProjectId()), id(diff.getDeliverableId()),
                id(diff.getProjectRepositoryId()), diff.getBaseRef(), diff.getHeadRef(), diff.getHeadCommit(),
                diff.getChangeStats(), iso(diff.getCreatedAt()));
    }

    private DiffFileResponse toResponse(DiffFileEntity f) {
        return new DiffFileResponse(id(f.getId()), f.getSequenceNo(), f.getPath(), f.getChangeType(), f.getAdditions(),
                f.getDeletions(), f.getBinaryFlag(), f.getHunks());
    }

    private DiffCommentResponse toResponse(DiffCommentEntity c) {
        return new DiffCommentResponse(id(c.getId()), id(c.getDiffId()), c.getPath(), c.getSide(), c.getLine(),
                c.getHunkId(), c.getCommitSha(), c.getBody(), id(c.getAuthorUserId()), iso(c.getCreatedAt()));
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

    private long parseLongCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            long v = Long.parseLong(cursor);
            if (v < 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "游标格式不合法");
        }
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    private String id(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }
}
