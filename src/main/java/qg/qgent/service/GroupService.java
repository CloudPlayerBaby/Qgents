package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.GroupCreateRequest;
import qg.qgent.dto.GroupMemberResponse;
import qg.qgent.dto.GroupResponse;
import qg.qgent.dto.GroupUpdateRequest;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.GroupAgentMapper;
import qg.qgent.mapper.ProjectMemberMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.RequirementGroupRepositoryMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 需求群业务：PROJECT_MAIN 自动创建、REQUIREMENT 群 CRUD 与归档（契约 §7 统一 Group）。
 */
@Service
public class GroupService {
    private static final String MAIN_GROUP_FALLBACK_TITLE = "项目主群";

    private final RequirementGroupMapper groupMapper;
    private final RequirementGroupRepositoryMapper groupRepositoryMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final GroupAgentMapper groupAgentMapper;
    private final AgentMapper agentMapper;
    private final ProjectAccessService access;

    public GroupService(RequirementGroupMapper groupMapper,
            RequirementGroupRepositoryMapper groupRepositoryMapper, ProjectMemberMapper projectMemberMapper,
            GroupAgentMapper groupAgentMapper, AgentMapper agentMapper, ProjectAccessService access) {
        this.groupMapper = groupMapper;
        this.groupRepositoryMapper = groupRepositoryMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.groupAgentMapper = groupAgentMapper;
        this.agentMapper = agentMapper;
        this.access = access;
    }

    /**
     * 监听项目创建事件，自动创建唯一 PROJECT_MAIN 群（契约 §7）。
     *
     * @param event 项目创建事件
     */
    @EventListener
    @Transactional
    public void onProjectCreated(ProjectCreatedEvent event) {
        ensureProjectMainGroup(event.getProjectId(), event.getCreatorUserId(), event.getProjectName());
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
        groupMapper.insert(group);
        return toResponse(groupMapper.selectById(group.getId()));
    }

    /**
     * 创建 REQUIREMENT 需求群；type 传 PROJECT_MAIN 返回 422 SYSTEM_GROUP_MANAGED。
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
        RequirementGroupEntity group = new RequirementGroupEntity();
        group.setId(UuidV7.next());
        group.setProjectId(projectId);
        group.setCreatedBy(actor);
        group.setName(body.getTitle().trim());
        group.setDescription(body.getDescription());
        group.setGroupType("REQUIREMENT");
        group.setStatus("ACTIVE");
        groupMapper.insert(group);
        for (UUID repositoryId : repositories) {
            groupRepositoryMapper.insertLink(group.getId(), repositoryId);
        }
        return toResponse(groupMapper.selectById(group.getId()));
    }

    /**
     * 项目全部群（含主群与已归档），按最近活跃排序。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @return 群视图列表
     */
    public List<GroupResponse> list(UUID actor, UUID projectId) {
        access.requireProjectMember(projectId, actor);
        return groupMapper.listByProject(projectId).stream().map(this::toResponse).toList();
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
        return toResponse(requireGroupInProject(projectId, groupId));
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
        return toResponse(groupMapper.selectById(groupId));
    }

    /**
     * 获取群成员列表（= 项目成员 + 参与群聊的 Agent，群内成员平等、无角色）。
     *
     * @param actor     当前用户 ID
     * @param projectId 项目 ID
     * @param groupId   群 ID
     * @return 成员视图列表
     */
    public List<GroupMemberResponse> members(UUID actor, UUID projectId, UUID groupId) {
        access.requireProjectMember(projectId, actor);
        requireGroupInProject(projectId, groupId);
        List<GroupMemberResponse> members = new ArrayList<>();
        projectMemberMapper.selectMembers(projectId).stream()
                .map(m -> new GroupMemberResponse(m.getUserId().toString(), m.getDisplayName(), m.getAvatarUrl(),
                        "USER"))
                .forEach(members::add);
        // 群内参与聊天的 Agent 一并作为成员返回（群成员 = 真实用户 + Agent 混合）
        for (UUID agentId : groupAgentMapper.selectAgentIds(groupId)) {
            AgentEntity agent = agentMapper.selectById(agentId);
            if (agent != null) {
                members.add(new GroupMemberResponse(agent.getId().toString(), agent.getName(), agent.getAvatar(),
                        "AGENT"));
            }
        }
        return members;
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
        return new GroupResponse(g.getId().toString(), g.getProjectId().toString(), g.getGroupType(),
                g.getName(), g.getDescription(), g.getStatus(), g.getLastMessageAt(), g.getCreatedAt(),
                groupRepositoryMapper.selectRepositoryIds(g.getId()).stream().map(UUID::toString).toList(),
                projectMemberMapper.countMembers(g.getProjectId()) + groupAgentMapper.selectAgentIds(g.getId()).size());
    }
}
