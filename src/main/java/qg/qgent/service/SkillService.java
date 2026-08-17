package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.SkillCreateRequest;
import qg.qgent.dto.SkillResponse;
import qg.qgent.dto.SkillUpdateRequest;
import qg.qgent.dto.UserSummary;
import qg.qgent.entity.SkillEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.SkillMapper;
import qg.qgent.mapper.UserMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Skill 业务：草稿创建、编辑、审核发布/拒绝、归档（契约 §8）。
 * <p>
 * 状态机：DRAFT → PENDING_REVIEW → PUBLISHED | REJECTED；PUBLISHED → ARCHIVED；
 * REJECTED 可重新提交审核。成员先建 PRIVATE，Project Admin 批准后发布为 PROJECT_SHARED。
 */
@Service
public class SkillService {
    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "PROJECT_SHARED");
    private static final Set<String> SUBMITTABLE = Set.of("DRAFT", "REJECTED");
    private static final Set<String> EDITABLE = Set.of("DRAFT", "PENDING_REVIEW");

    private final SkillMapper skillMapper;
    private final ProjectAccessService access;
    private final UserMapper userMapper;
    private final EventService eventService;

    public SkillService(SkillMapper skillMapper, ProjectAccessService access, UserMapper userMapper,
                        EventService eventService) {
        this.skillMapper = skillMapper;
        this.access = access;
        this.userMapper = userMapper;
        this.eventService = eventService;
    }

    /**
     * 创建 Skill。免审批规则：
     * <ul>
     *   <li>PRIVATE（仅创建者自己可用，不共享）：任何人创建即 PUBLISHED，无需审核；</li>
     *   <li>PROJECT_SHARED（项目共享）：Project Admin 自建直接 PUBLISHED，普通成员创建为
     *       DRAFT，经提交审核后由 Admin 发布。</li>
     * </ul>
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param request   创建请求
     * @return Skill 视图
     */
    @Transactional
    public SkillResponse create(UUID actor, UUID projectId, SkillCreateRequest request) {
        access.requireProjectMember(projectId, actor);
        String visibility = request.getVisibility() == null || request.getVisibility().isBlank()
                ? "PRIVATE"
                : request.getVisibility().trim();
        if (!VISIBILITIES.contains(visibility)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_VISIBILITY",
                    "可见性仅支持 PRIVATE 或 PROJECT_SHARED");
        }
        SkillEntity skill = new SkillEntity();
        skill.setId(UuidV7.next());
        skill.setProjectId(projectId);
        skill.setCreatedBy(actor);
        skill.setName(request.getName().trim());
        skill.setContent(request.getContent().trim());
        skill.setTags(request.getTags());
        skill.setVisibility(visibility);
        skill.setStatus("DRAFT");
        skillMapper.insert(skill);
        boolean autoPublish = "PRIVATE".equals(visibility) || access.isProjectAdmin(projectId, actor);
        if (autoPublish) {
            // PRIVATE 仅自己用无需审核；PROJECT_SHARED 的 Admin 自建免审批
            skill.setStatus("PUBLISHED");
            skill.setReviewerId(actor);
            skill.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
            skillMapper.updateById(skill);
            eventService.publish(projectId, null, "skill.published", id(skill.getId()),
                    deliveryPayload(projectId, skill.getId()));
        }
        return toResponse(skillMapper.selectById(skill.getId()));
    }

    /**
     * 项目内查询 Skill：仅返回 PROJECT_SHARED 或自己创建的，支持状态、标签过滤。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param status    状态过滤，可为空
     * @param tag       标签过滤，可为空
     * @return Skill 列表
     */
    public List<SkillResponse> list(UUID actor, UUID projectId, String status, String tag) {
        access.requireProjectMember(projectId, actor);
        return skillMapper.listSkills(projectId, actor, blankToNull(status), blankToNull(tag))
                .stream().map(this::toResponse).toList();
    }

    /**
     * 获取 Skill；他人 PRIVATE Skill 视为不可见。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param skillId   Skill ID
     * @return Skill 视图
     */
    public SkillResponse get(UUID actor, UUID projectId, UUID skillId) {
        access.requireProjectMember(projectId, actor);
        SkillEntity skill = requireSkillInProject(projectId, skillId);
        if ("PRIVATE".equals(skill.getVisibility()) && !skill.getCreatedBy().equals(actor)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "Skill 不存在或无权访问");
        }
        return toResponse(skill);
    }

    /**
     * 编辑草稿或审核中内容；仅创建者或 Project Admin，且状态为 DRAFT/PENDING_REVIEW。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param skillId   Skill ID
     * @param request   修改请求（PATCH 语义）
     * @return Skill 视图
     */
    @Transactional
    public SkillResponse update(UUID actor, UUID projectId, UUID skillId, SkillUpdateRequest request) {
        SkillEntity skill = requireEditable(actor, projectId, skillId);
        if (request.getName() != null) {
            skill.setName(request.getName().trim());
        }
        if (request.getContent() != null) {
            skill.setContent(request.getContent().trim());
        }
        if (request.getTags() != null) {
            skill.setTags(request.getTags());
        }
        skillMapper.updateById(skill);
        return toResponse(skillMapper.selectById(skillId));
    }

    /**
     * 提交审核；仅创建者或 Project Admin，且状态为 DRAFT/REJECTED。
     * PRIVATE Skill 创建即 PUBLISHED（仅自己用，不进交付中心），不支持转共享审核。
     */
    @Transactional
    public SkillResponse submitReview(UUID actor, UUID projectId, UUID skillId) {
        SkillEntity skill = requireOwned(actor, projectId, skillId);
        if (!SUBMITTABLE.contains(skill.getStatus())) {
            throw stateConflict(skill.getStatus());
        }
        skill.setStatus("PENDING_REVIEW");
        skill.setSubmittedBy(actor);
        skill.setSubmittedAt(LocalDateTime.now(ZoneOffset.UTC));
        skillMapper.updateById(skill);
        eventService.publish(projectId, null, "skill.submit-review", id(skillId),
                deliveryPayload(projectId, skillId));
        return toResponse(skillMapper.selectById(skillId));
    }

    /**
     * 发布 Skill（Project Admin）；仅 PENDING_REVIEW，发布后自动转为 PROJECT_SHARED。
     */
    @Transactional
    public SkillResponse approve(UUID actor, UUID projectId, UUID skillId) {
        access.requireProjectAdmin(projectId, actor);
        SkillEntity skill = requireSkillInProject(projectId, skillId);
        if (!"PENDING_REVIEW".equals(skill.getStatus())) {
            throw stateConflict(skill.getStatus());
        }
        skill.setStatus("PUBLISHED");
        skill.setVisibility("PROJECT_SHARED");
        skill.setReviewerId(actor);
        skill.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        skillMapper.updateById(skill);
        eventService.publish(projectId, null, "skill.published", id(skillId),
                deliveryPayload(projectId, skillId));
        return toResponse(skillMapper.selectById(skillId));
    }

    /**
     * 拒绝 Skill 并给出原因（Project Admin）；仅 PENDING_REVIEW。
     */
    @Transactional
    public SkillResponse reject(UUID actor, UUID projectId, UUID skillId, String reason) {
        access.requireProjectAdmin(projectId, actor);
        SkillEntity skill = requireSkillInProject(projectId, skillId);
        if (!"PENDING_REVIEW".equals(skill.getStatus())) {
            throw stateConflict(skill.getStatus());
        }
        skill.setStatus("REJECTED");
        skill.setReviewerId(actor);
        skill.setRejectionReason(reason);
        skill.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
        skillMapper.updateById(skill);
        eventService.publish(projectId, null, "skill.rejected", id(skillId),
                deliveryPayload(projectId, skillId));
        return toResponse(skillMapper.selectById(skillId));
    }

    /**
     * 下线已发布 Skill（Project Admin）；仅 PUBLISHED。
     */
    @Transactional
    public SkillResponse archive(UUID actor, UUID projectId, UUID skillId) {
        access.requireProjectAdmin(projectId, actor);
        SkillEntity skill = requireSkillInProject(projectId, skillId);
        if (!"PUBLISHED".equals(skill.getStatus())) {
            throw stateConflict(skill.getStatus());
        }
        skill.setStatus("ARCHIVED");
        skillMapper.updateById(skill);
        eventService.publish(projectId, null, "skill.archived", id(skillId),
                deliveryPayload(projectId, skillId));
        return toResponse(skillMapper.selectById(skillId));
    }

    /**
     * Skill 交付事件 payload（契约 v1.8.0 §20 DeliveryEventPayload 基座）。
     */
    private java.util.Map<String, Object> deliveryPayload(UUID projectId, UUID skillId) {
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("projectId", id(projectId));
        payload.put("resourceType", "SKILL");
        payload.put("resourceId", id(skillId));
        payload.put("eventVersion", 1);
        payload.put("updatedAt", LocalDateTime.now(ZoneOffset.UTC).toInstant(ZoneOffset.UTC).toString());
        return payload;
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private SkillEntity requireSkillInProject(UUID projectId, UUID skillId) {
        SkillEntity skill = skillMapper.selectById(skillId);
        if (skill == null || !skill.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "Skill 不存在或无权访问");
        }
        return skill;
    }

    private SkillEntity requireOwned(UUID actor, UUID projectId, UUID skillId) {
        access.requireProjectMember(projectId, actor);
        SkillEntity skill = requireSkillInProject(projectId, skillId);
        if (!skill.getCreatedBy().equals(actor)) {
            access.requireProjectAdmin(projectId, actor);
        }
        return skill;
    }

    private SkillEntity requireEditable(UUID actor, UUID projectId, UUID skillId) {
        SkillEntity skill = requireOwned(actor, projectId, skillId);
        if (!EDITABLE.contains(skill.getStatus())) {
            throw stateConflict(skill.getStatus());
        }
        return skill;
    }

    private ApiException stateConflict(String status) {
        return new ApiException(HttpStatus.CONFLICT, "SKILL_STATE_CONFLICT", "Skill 当前状态不允许该操作: " + status);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private SkillResponse toResponse(SkillEntity skill) {
        return new SkillResponse(skill.getId().toString(), skill.getProjectId().toString(), skill.getName(),
                skill.getContent(), skill.getTags(), skill.getVisibility(), skill.getStatus(),
                userSummary(skill.getCreatedBy()), userSummary(skill.getReviewerId()), skill.getRejectionReason(),
                skill.getReviewedAt(), skill.getCreatedAt(), skill.getUpdatedAt());
    }

    private UserSummary userSummary(UUID userId) {
        if (userId == null) {
            return null;
        }
        UserEntity user = userMapper.selectById(userId);
        return user == null ? null
                : new UserSummary(user.getId().toString(), user.getDisplayName(), user.getAvatarUrl());
    }
}
