package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.*;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.UserEntity;
import qg.qgent.mapper.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 需求群业务：PROJECT_MAIN 自动创建、REQUIREMENT 群 CRUD 与归档（契约 §7 统一 Group）。
 */
@Service
public class GroupService {
    private static final String MAIN_GROUP_FALLBACK_TITLE = "项目主群";

    private final RequirementGroupMapper groupMapper;
    private final RequirementGroupRepositoryMapper groupRepositoryMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ProjectMapper projectMapper;
    private final GroupAgentMapper groupAgentMapper;
    private final GroupMemberMapper groupMemberMapper;
    private final AgentMapper agentMapper;
    private final UserMapper userMapper;
    private final MessageMapper messageMapper;
    private final GroupReadStateMapper groupReadStateMapper;
    private final ProjectAccessService access;
    private final EventService eventService;
    private final IdempotencyService idempotencyService;

    public GroupService(RequirementGroupMapper groupMapper,
                        RequirementGroupRepositoryMapper groupRepositoryMapper, ProjectMemberMapper projectMemberMapper,
                        ProjectMapper projectMapper, GroupAgentMapper groupAgentMapper, GroupMemberMapper groupMemberMapper,
                        AgentMapper agentMapper, UserMapper userMapper,
                        MessageMapper messageMapper, GroupReadStateMapper groupReadStateMapper,
                        ProjectAccessService access, EventService eventService, IdempotencyService idempotencyService) {
        this.groupMapper = groupMapper;
        this.groupRepositoryMapper = groupRepositoryMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.projectMapper = projectMapper;
        this.groupAgentMapper = groupAgentMapper;
        this.groupMemberMapper = groupMemberMapper;
        this.agentMapper = agentMapper;
        this.userMapper = userMapper;
        this.messageMapper = messageMapper;
        this.groupReadStateMapper = groupReadStateMapper;
        this.access = access;
        this.eventService = eventService;
        this.idempotencyService = idempotencyService;
    }

    /**
     * 监听项目创建事件，自动创建唯一 PROJECT_MAIN 群（契约 §7）。
     *
     * @param event 项目创建事件
     */
    @EventListener
    @Transactional
    public void onProjectCreated(ProjectCreatedEvent event) {
        ensureProjectMainGroup(event.projectId(), event.creatorUserId(), event.projectName());
    }

    /**
     * 确保项目有且仅有一个 PROJECT_MAIN 群（幂等，可被项目域直接调用）。
     *
     * @param projectId   项目 ID
     * @param createdBy   创建者（PROJECT_ADMIN）用户 ID
     * @param projectName 项目名，用作主群标题
     * @return 主群视图
     */
    @Transactional
    public GroupResponse ensureProjectMainGroup(UUID projectId, UUID createdBy, String projectName) {
        RequirementGroupEntity existing = groupMapper.selectOne(Wrappers.<RequirementGroupEntity>lambdaQuery()
                .eq(RequirementGroupEntity::getProjectId, projectId)
                .eq(RequirementGroupEntity::getGroupType, "PROJECT_MAIN"));
        if (existing != null) {
            return toResponse(existing);
        }
        String title = projectName == null || projectName.isBlank() ? MAIN_GROUP_FALLBACK_TITLE
                : projectName.trim();
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(UuidV7.next());
        group.setProjectId(projectId);
        group.setCreatedBy(createdBy);
        group.setName(title);
        group.setGroupType("PROJECT_MAIN");
        group.setStatus("ACTIVE");
        group.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        groupMapper.insert(group);
        eventService.publish(projectId, group.getId(), "group.created", id(group.getId()),
                Map.of("projectId", id(projectId), "groupId", id(group.getId())));
        return toResponse(groupMapper.selectById(group.getId()));
    }

    /**
     * 创建 REQUIREMENT 需求群；type 传 PROJECT_MAIN 返回 422 SYSTEM_GROUP_MANAGED。
     * <p>
     * 初始成员（契约 2026-08-17 群成员选择与管理）：{@code memberIds} 可选，每项必须是
     * 该项目成员（非项目成员 422 GROUP_MEMBER_NOT_PROJECT_MEMBER）；创建者自动入群
     * （无论是否在 memberIds 中），与 memberIds 去重；空/不传时群内仅创建者。
     * Agent 成员不在此接口管理（由编排按需加入 group_agents）。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param body      创建请求
     * @return 新建群视图
     */
    @Transactional
    public GroupResponse create(UUID actor, UUID projectId, GroupCreateRequest body) {
        access.requireProjectMember(projectId, actor);
        String type = body.getType() == null || body.getType().isBlank() ? "REQUIREMENT" : body.getType().trim();
        if (!"REQUIREMENT".equals(type)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SYSTEM_GROUP_MANAGED",
                    "项目主群由系统管理，不能通过该接口创建");
        }
        List<UUID> repositories = validateRepositories(projectId, body.getRepositoryIds());
        List<UUID> initialMembers = validateGroupMembers(projectId, body.getMemberIds());
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(UuidV7.next());
        group.setProjectId(projectId);
        group.setCreatedBy(actor);
        group.setName(body.getTitle().trim());
        group.setDescription(body.getDescription());
        group.setGroupType("REQUIREMENT");
        group.setStatus("ACTIVE");
        group.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        groupMapper.insert(group);
        for (UUID repositoryId : repositories) {
            groupRepositoryMapper.insertLink(group.getId(), repositoryId);
        }
        // 初始群成员：创建者自动入群（去重），memberIds 其余项逐一写入
        groupMemberMapper.insertMember(group.getId(), actor);
        for (UUID memberId : initialMembers) {
            if (!memberId.equals(actor)) {
                groupMemberMapper.insertMember(group.getId(), memberId);
            }
        }
        eventService.publish(projectId, group.getId(), "group.created", id(group.getId()),
                Map.of("projectId", id(projectId), "groupId", id(group.getId())));
        return toResponse(groupMapper.selectById(group.getId()));
    }

    /**
     * 项目全部群（含主群与已归档），按最近活跃排序；附带每群最新消息摘要。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @return 群视图列表
     */
    public List<GroupResponse> list(UUID actor, UUID projectId) {
        access.requireProjectMember(projectId, actor);
        Map<UUID, GroupLatestMessageRow> latestByGroup = messageMapper.selectLatestByProject(projectId).stream()
                .collect(Collectors.toMap(GroupLatestMessageRow::getRequirementGroupId, Function.identity()));
        Map<UUID, Long> unreadByGroup = countUnread(projectId, actor);
        return groupMapper.listByProject(projectId).stream()
                .map(group -> toResponse(group, latestByGroup.get(group.getId()), unreadByGroup.get(group.getId())))
                .toList();
    }

    /**
     * 群聊工作台聚合：一次返回当前用户所有可见项目的 PROJECT_MAIN 主群（消除三层 N+1）。
     * <p>
     * 通过 {@link ProjectMapper#selectAccessibleByUser} 取全部可见项目，批量取这些项目的
     * PROJECT_MAIN 群；每群附带最新消息摘要、成员数与未读数。可见项目/主群数量不大，
     * 未读按项目分次统计（MVP 可接受）。
     *
     * @param actor 当前用户 ID
     * @return 可见项目的主群视图列表（含最新消息与 unreadCount）
     */
    public List<GroupResponse> mainGroups(UUID actor) {
        List<UUID> projectIds = projectMapper.selectAccessibleByUser(actor).stream()
                .map(ProjectMembershipView::getId).toList();
        if (projectIds.isEmpty()) {
            return List.of();
        }
        List<RequirementGroupEntity> mainGroups = groupMapper.selectMainGroupsByProjectIds(projectIds);
        // 逐项目取主群最新消息摘要（key: groupId）
        Map<UUID, GroupLatestMessageRow> latestByGroup = new java.util.HashMap<>();
        for (UUID projectId : projectIds) {
            messageMapper.selectLatestByProject(projectId).forEach(
                    row -> latestByGroup.put(row.getRequirementGroupId(), row));
        }
        // 逐项目统计未读（key: groupId）
        Map<UUID, Long> unreadByGroup = new java.util.HashMap<>();
        for (UUID projectId : projectIds) {
            countUnread(projectId, actor).forEach(unreadByGroup::put);
        }
        return mainGroups.stream()
                .map(g -> toResponse(g, latestByGroup.get(g.getId()), unreadByGroup.get(g.getId())))
                .toList();
    }

    /**
     * 计算某用户在项目各群的未读数（排除本人消息），返回群 ID → 未读数。
     */
    private Map<UUID, Long> countUnread(UUID projectId, UUID actor) {
        return messageMapper.countUnreadByProject(projectId, actor).stream()
                .collect(Collectors.toMap(GroupUnreadRow::getGroupId, GroupUnreadRow::getUnread));
    }

    /**
     * 获取群详情；群不属于该项目时返回 404。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   群 ID
     * @return 群视图
     */
    public GroupResponse get(UUID actor, UUID projectId, UUID groupId) {
        access.requireProjectMember(projectId, actor);
        RequirementGroupEntity group = requireGroupInProject(projectId, groupId);
        Map<UUID, Long> unreadByGroup = countUnread(projectId, actor);
        GroupResponse response = toResponse(group, null, unreadByGroup.get(groupId));
        return response;
    }

    /**
     * 修改群标题、描述和关联仓库；仅创建者或 Project Admin 可操作（PATCH 语义）。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   群 ID
     * @param body      修改请求
     * @return 更新后的群视图
     */
    @Transactional
    public GroupResponse update(UUID actor, UUID projectId, UUID groupId, GroupUpdateRequest body) {
        access.requireProjectMember(projectId, actor);
        RequirementGroupEntity group = requireGroupInProject(projectId, groupId);
        if (!group.getCreatedBy().equals(actor)) {
            access.requireProjectAdmin(projectId, actor);
        }
        if (body.getRepositoryIds() != null) {
            List<UUID> repositories = validateRepositories(projectId, body.getRepositoryIds());
            groupRepositoryMapper.deleteByGroup(groupId);
            for (UUID repositoryId : repositories) {
                groupRepositoryMapper.insertLink(groupId, repositoryId);
            }
        }
        groupMapper.update(null, Wrappers.<RequirementGroupEntity>lambdaUpdate()
                .set(body.getTitle() != null, RequirementGroupEntity::getName,
                        body.getTitle() == null ? null : body.getTitle().trim())
                .set(body.getDescription() != null, RequirementGroupEntity::getDescription, body.getDescription())
                .eq(RequirementGroupEntity::getId, groupId));
        eventService.publish(projectId, groupId, "group.updated", id(groupId),
                Map.of("projectId", id(projectId), "groupId", id(groupId)));
        return toResponse(groupMapper.selectById(groupId));
    }

    /**
     * 归档需求群；仅创建者或 Project Admin 可操作，仅 REQUIREMENT 可归档，PROJECT_MAIN 恒为 ACTIVE。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   群 ID
     * @return 归档后的群视图
     */
    @Transactional
    public GroupResponse archive(UUID actor, UUID projectId, UUID groupId) {
        access.requireProjectMember(projectId, actor);
        RequirementGroupEntity group = requireGroupInProject(projectId, groupId);
        if (!group.getCreatedBy().equals(actor)) {
            access.requireProjectAdmin(projectId, actor);
        }
        if ("PROJECT_MAIN".equals(group.getGroupType())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SYSTEM_GROUP_MANAGED", "项目主群不可归档");
        }
        if ("ARCHIVED".equals(group.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "GROUP_ALREADY_ARCHIVED", "需求群已归档");
        }
        group.setStatus("ARCHIVED");
        groupMapper.updateById(group);
        eventService.publish(projectId, groupId, "group.archived", id(groupId),
                Map.of("projectId", id(projectId), "groupId", id(groupId)));
        return toResponse(groupMapper.selectById(groupId));
    }

    /**
     * 获取群成员列表（群成员 = 显式群用户成员 + 参与群聊的 Agent，群内成员平等、无角色）。
     * <p>
     * PROJECT_MAIN 主群成员恒为全部项目成员（系统管理，不查 group_members）；
     * REQUIREMENT 需求群成员 = group_members（join 用户基础信息，含 email）+ group_agents。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   群 ID
     * @return 成员视图列表
     */
    public List<GroupMemberResponse> members(UUID actor, UUID projectId, UUID groupId) {
        access.requireProjectMember(projectId, actor);
        RequirementGroupEntity group = requireGroupInProject(projectId, groupId);
        List<GroupMemberResponse> members = new ArrayList<>();
        if ("PROJECT_MAIN".equals(group.getGroupType())) {
            projectMemberMapper.selectMembers(projectId).stream()
                    .map(m -> new GroupMemberResponse(m.getUserId().toString(), m.getDisplayName(), m.getAvatarUrl(),
                            null, "USER"))
                    .forEach(members::add);
        } else {
            groupMemberMapper.selectMembersWithUsers(groupId).stream()
                    .map(m -> new GroupMemberResponse(m.getUserId().toString(), m.getDisplayName(), m.getAvatarUrl(),
                            m.getEmail(), "USER"))
                    .forEach(members::add);
        }
        // 群内参与聊天的 Agent 一并作为成员返回（群成员 = 真实用户 + Agent 混合）
        for (UUID agentId : groupAgentMapper.selectAgentIds(groupId)) {
            AgentEntity agent = agentMapper.selectById(agentId);
            if (agent != null) {
                members.add(new GroupMemberResponse(agent.getId().toString(), agent.getName(), agent.getAvatar(),
                        null, "AGENT"));
            }
        }
        return members;
    }

    /**
     * 邀请项目成员入群（契约 2026-08-17 群成员选择与管理）。
     * <p>
     * 权限：群创建者或 Project Admin；被邀请人必须是该项目成员（否则 422
     * GROUP_MEMBER_NOT_PROJECT_MEMBER）；已在群内时幂等返回；PROJECT_MAIN 主群
     * 422 SYSTEM_GROUP_MANAGED；Agent 不在此接口管理。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   群 ID
     * @param userId    被邀请的项目成员用户 ID
     * @return 被邀请成员视图（含 email）
     */
    @Transactional
    public GroupMemberResponse addMember(UUID actor, UUID projectId, UUID groupId, UUID userId) {
        access.requireProjectMember(projectId, actor);
        RequirementGroupEntity group = requireGroupInProject(projectId, groupId);
        if ("PROJECT_MAIN".equals(group.getGroupType())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SYSTEM_GROUP_MANAGED",
                    "项目主群成员由系统管理，不能邀请");
        }
        requireGroupAdmin(projectId, group, actor);
        if (projectMemberMapper.selectByProjectAndUser(projectId, userId) == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GROUP_MEMBER_NOT_PROJECT_MEMBER",
                    "被邀请人不是该项目成员");
        }
        groupMemberMapper.insertMember(groupId, userId);
        UserEntity user = userMapper.selectById(userId);
        eventService.publish(projectId, groupId, "group.member.updated", id(groupId),
                Map.of("projectId", id(projectId), "groupId", id(groupId)));
        return new GroupMemberResponse(id(userId), user == null ? null : user.getDisplayName(),
                user == null ? null : user.getAvatarUrl(), user == null ? null : user.getEmail(), "USER");
    }

    /**
     * 移出群聊（契约 2026-08-17 群成员选择与管理；与 leave「退出项目」语义不同，本接口只移出群）。
     * <p>
     * 权限：群创建者或 Project Admin；群创建者本人不可被移出（422 GROUP_CREATOR_NOT_REMOVABLE）；
     * 目标不在群内 404 GROUP_MEMBER_NOT_FOUND；PROJECT_MAIN 主群 422 SYSTEM_GROUP_MANAGED；
     * Agent 不在此接口管理。
     * 移出后该成员失去该群（及其消息/任务卡片）的访问权限，但保留项目成员身份。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   群 ID
     * @param userId    被移出的用户 ID
     */
    @Transactional
    public void removeMember(UUID actor, UUID projectId, UUID groupId, UUID userId) {
        access.requireProjectMember(projectId, actor);
        RequirementGroupEntity group = requireGroupInProject(projectId, groupId);
        if ("PROJECT_MAIN".equals(group.getGroupType())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SYSTEM_GROUP_MANAGED",
                    "项目主群成员由系统管理，不能移出");
        }
        requireGroupAdmin(projectId, group, actor);
        if (group.getCreatedBy().equals(userId)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GROUP_CREATOR_NOT_REMOVABLE",
                    "群创建者不可被移出");
        }
        if (groupMemberMapper.deleteMember(groupId, userId) == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_MEMBER_NOT_FOUND", "目标用户不在群内");
        }
        eventService.publish(projectId, groupId, "group.member.updated", id(groupId),
                Map.of("projectId", id(projectId), "groupId", id(groupId)));
    }

    /**
     * 校验当前用户可访问该群（消息/任务卡片可见性，契约 2026-08-17 严格收紧）：
     * 必须是项目成员；PROJECT_MAIN 主群所有项目成员可见；REQUIREMENT 需求群仅群成员
     * （group_members 或创建者兜底）可见，否则 403 GROUP_MEMBER_REQUIRED。
     *
     * @param projectId 项目 ID
     * @param groupId   群 ID
     * @param actor     当前用户 ID
     */
    public void requireGroupMember(UUID projectId, UUID groupId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        RequirementGroupEntity group = requireGroupInProject(projectId, groupId);
        if ("PROJECT_MAIN".equals(group.getGroupType())) {
            return;
        }
        if (isGroupMember(groupId, actor)) {
            return;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "GROUP_MEMBER_REQUIRED",
                "你不是该需求群成员，无法访问");
    }

    /**
     * 判断用户是否为需求群显式成员（创建者兜底为成员；PROJECT_MAIN 由调用方另行处理）。
     *
     * @param groupId 群 ID
     * @param userId  用户 ID
     * @return 是否群成员
     */
    public boolean isGroupMember(UUID groupId, UUID userId) {
        if (groupMemberMapper.countMember(groupId, userId) > 0) {
            return true;
        }
        RequirementGroupEntity group = groupMapper.selectById(groupId);
        return group != null && group.getCreatedBy().equals(userId);
    }

    /**
     * 校验用户可见的需求群 ID 列表（任务中心等按群过滤用）：用户是群成员的需求群
     * （含创建者兜底）；PROJECT_MAIN 主群单独由项目成员可见性覆盖，不在此列表。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @return 用户可见的需求群 ID 列表
     */
    public List<UUID> visibleRequirementGroupIds(UUID projectId, UUID userId) {
        return groupMemberMapper.selectGroupIdsByUser(projectId, userId);
    }

    /**
     * 用户在该项目全部可见群 ID（任务中心按群过滤）：项目全部 PROJECT_MAIN 主群
     * （主群恒对项目成员可见）+ 用户已加入的 REQUIREMENT 需求群。
     *
     * @param projectId 项目 ID
     * @param userId    用户 ID
     * @return 可见群 ID 列表
     */
    public List<UUID> visibleGroupIds(UUID projectId, UUID userId) {
        List<UUID> result = new ArrayList<>();
        groupMapper.selectList(Wrappers.<RequirementGroupEntity>lambdaQuery()
                        .eq(RequirementGroupEntity::getProjectId, projectId)
                        .eq(RequirementGroupEntity::getGroupType, "PROJECT_MAIN"))
                .forEach(group -> result.add(group.getId()));
        result.addAll(groupMemberMapper.selectGroupIdsByUser(projectId, userId));
        return result;
    }

    /**
     * 群管理权限：群创建者或 Project Admin，否则 403 FORBIDDEN（与归档权限一致）。
     */
    private void requireGroupAdmin(UUID projectId, RequirementGroupEntity group, UUID actor) {
        if (!group.getCreatedBy().equals(actor)) {
            access.requireProjectAdmin(projectId, actor);
        }
    }

    /**
     * 校验初始成员列表全部为项目成员；空/不传返回空列表。
     */
    private List<UUID> validateGroupMembers(UUID projectId, List<UUID> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return List.of();
        }
        List<UUID> distinct = memberIds.stream().distinct().toList();
        for (UUID memberId : distinct) {
            if (projectMemberMapper.selectByProjectAndUser(projectId, memberId) == null) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GROUP_MEMBER_NOT_PROJECT_MEMBER",
                        "初始成员不是该项目成员");
            }
        }
        return distinct;
    }

    /**
     * 当前用户退出群聊（即移出本项目成员，契约 §7 已确认语义）。
     * <p>
     * 移出后失去该项目全部群/消息/资源访问权限；最后一名 Project Admin 不可退出。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   群 ID
     */
    @Transactional
    public void leave(UUID actor, UUID projectId, UUID groupId) {
        access.requireProjectMember(projectId, actor);
        requireGroupInProject(projectId, groupId);
        ProjectMemberEntity member = projectMemberMapper.selectByProjectAndUser(projectId, actor);
        if (member != null && "PROJECT_ADMIN".equals(member.getRole())) {
            int admins = projectMemberMapper.countAdmins(projectId);
            if (admins <= 1) {
                throw new ApiException(HttpStatus.CONFLICT, "PROJECT_ADMIN_CANNOT_LEAVE",
                        "最后一名项目 Admin 不可退出项目");
            }
        }
        projectMemberMapper.deleteByProjectAndUser(projectId, actor);
        eventService.publish(projectId, groupId, "group.member.updated", id(groupId),
                Map.of("projectId", id(projectId), "groupId", id(groupId)));
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    /**
     * 标记已读（进群全读语义，契约 §7 未读权威化补充）。
     * <p>
     * 将当前用户在该群的已读游标推进到该群当前最新消息的 sequence（全部已读）；
     * 游标只前进不后退，重复调用幂等。需携带 Idempotency-Key（写操作统一约定）。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   需求群 ID
     * @param idempotencyKey Idempotency-Key 头
     * @return 推进后的已读状态（未读数恒 0）
     */
    public GroupReadResponse markRead(UUID actor, UUID projectId, UUID groupId, String idempotencyKey) {
        access.requireProjectMember(projectId, actor);
        requireGroupInProject(projectId, groupId);
        return idempotencyService.execute(actor,
                "POST:/projects/{projectId}/groups/{groupId}/read", idempotencyKey,
                Map.of("projectId", projectId, "groupId", groupId), 200, GroupReadResponse.class,
                () -> {
                    // 最新游标 = 群内消息最大 sequence（无消息时为 0），进群全读推进到最新
                    long latest = nextReadSequence(groupId);
                    groupReadStateMapper.upsertSequence(actor, groupId, latest);
                    return new GroupReadResponse(id(groupId), latest, 0L);
                });
    }

    /**
     * 取该群当前最新消息序号作为已读游标目标（无消息时为 0）。
     * nextSequence = max(sequence_no)+1，故最新 max = nextSequence - 1；空群 next=1 → 0。
     */
    private long nextReadSequence(UUID groupId) {
        Long next = messageMapper.nextSequence(groupId);
        return (next == null || next <= 1) ? 0L : next - 1;
    }

    private List<UUID> validateRepositories(UUID projectId, List<UUID> repositoryIds) {
        if (repositoryIds == null || repositoryIds.isEmpty()) {
            return List.of();
        }
        List<UUID> distinct = repositoryIds.stream().distinct().toList();
        for (UUID repositoryId : distinct) {
            Integer count = groupRepositoryMapper.countRepositoryInProject(projectId, repositoryId);
            if (count == null || count == 0) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPOSITORY_NOT_IN_PROJECT",
                        "仓库未绑定到该项目");
            }
        }
        return distinct;
    }

    private RequirementGroupEntity requireGroupInProject(UUID projectId, UUID groupId) {
        RequirementGroupEntity group = groupMapper.selectById(groupId);
        if (group == null || !group.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "GROUP_NOT_FOUND", "群不存在或无权访问");
        }
        return group;
    }

    private GroupResponse toResponse(RequirementGroupEntity g) {
        return toResponse(g, null, null);
    }

    private GroupResponse toResponse(RequirementGroupEntity g, GroupLatestMessageRow latest) {
        return toResponse(g, latest, null);
    }

    private GroupResponse toResponse(RequirementGroupEntity g, GroupLatestMessageRow latest, Long unreadCount) {
        GroupLatestMessage latestMessage = latest == null ? null
                : new GroupLatestMessage(latest.getSenderName(), latest.getText(), latest.getMessageType());
        // 群成员数（契约 2026-08-17）：PROJECT_MAIN 主群 = 全部项目成员；REQUIREMENT 需求群 = 显式群成员
        long members = ("PROJECT_MAIN".equals(g.getGroupType())
                ? projectMemberMapper.countMembers(g.getProjectId())
                : groupMemberMapper.countMembers(g.getId()))
                + groupAgentMapper.selectAgentIds(g.getId()).size();
        return new GroupResponse(g.getId().toString(), g.getProjectId().toString(), g.getGroupType(),
                g.getName(), g.getDescription(), g.getStatus(), id(g.getCreatedBy()), iso(g.getLastMessageAt()),
                iso(g.getLastMessageAt() != null ? g.getLastMessageAt() : g.getCreatedAt()), latestMessage,
                iso(g.getCreatedAt()),
                groupRepositoryMapper.selectRepositoryIds(g.getId()).stream().map(UUID::toString).toList(),
                members, unreadCount == null ? 0L : unreadCount);
    }

    /**
     * 时间统一序列化为 UTC 并带 Z 后缀（ISO8601），避免前端按本地时区误解析。
     */
    private String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}
