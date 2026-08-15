package qg.qgent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.dto.AgentAssignmentListItem;
import qg.qgent.dto.AgentResponse;
import qg.qgent.dto.AgentRuntimeSummary;
import qg.qgent.dto.PageInfo;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.GroupAgentMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.TeamMemberMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Team-scoped Agent visibility and read operations.
 */
@Service
public class AgentService {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private static final List<String> ACTIVE_RUN_STATUSES = List.of("RUNNING", "QUEUED", "WAITING_INPUT",
            "WAITING_APPROVAL", "BLOCKED", "CANCELLING");

    private final AgentMapper agentMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final ProjectAccessService projectAccess;
    private final GroupAgentMapper groupAgentMapper;
    private final RequirementGroupMapper requirementGroupMapper;
    private final TaskRunMapper taskRunMapper;

    public AgentService(AgentMapper agentMapper, TeamMemberMapper teamMemberMapper,
                        ProjectAccessService projectAccess, GroupAgentMapper groupAgentMapper,
                        RequirementGroupMapper requirementGroupMapper, TaskRunMapper taskRunMapper) {
        this.agentMapper = agentMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.projectAccess = projectAccess;
        this.groupAgentMapper = groupAgentMapper;
        this.requirementGroupMapper = requirementGroupMapper;
        this.taskRunMapper = taskRunMapper;
    }

    public List<AgentResponse> list(UUID actorId, UUID teamId) {
        if (teamMemberMapper.selectByTeamAndUser(teamId, actorId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_RESOURCE_NOT_FOUND", "团队资源不存在或不可见");
        }
        return agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                        .eq(AgentEntity::getTeamId, teamId)
                        .eq(AgentEntity::getStatus, "ACTIVE")
                        .orderByAsc(AgentEntity::getName))
                .stream()
                .filter(agent -> "TEAM".equals(agent.getVisibility()) || actorId.equals(agent.getCreatedBy()))
                .map(agent -> toResponse(agent, actorId.equals(agent.getCreatedBy())))
                .toList();
    }

    /**
     * Agent 分配列表（契约 v1.8.0 §20，成员 B B04）。
     * <p>
     * REQUIREMENT_GROUP 数据源为 group_agents（Agent 参与的需求群，按项目隔离）；
     * WORKFLOW 当前无数据源，返回空列表不伪造。
     */
    public PagedApiResponse<AgentAssignmentListItem> assignments(UUID projectId, UUID agentId, UUID actor,
                                                                 String type, String cursor, Integer limit,
                                                                 String requestId) {
        projectAccess.requireProjectMember(projectId, actor);
        requireAgent(agentId);
        List<AgentAssignmentListItem> all = new ArrayList<>();
        if (type == null || "REQUIREMENT_GROUP".equals(type)) {
            List<UUID> groupIds = groupAgentMapper.selectGroupIdsByAgent(projectId, agentId);
            if (!groupIds.isEmpty()) {
                Map<UUID, RequirementGroupEntity> groupById = requirementGroupMapper
                        .selectList(Wrappers.<RequirementGroupEntity>lambdaQuery()
                                .in(RequirementGroupEntity::getId, groupIds))
                        .stream().collect(Collectors.toMap(RequirementGroupEntity::getId, Function.identity()));
                groupIds.stream().map(groupById::get).filter(java.util.Objects::nonNull)
                        .forEach(group -> all.add(new AgentAssignmentListItem("REQUIREMENT_GROUP",
                                id(group.getId()), group.getName(),
                                "ACTIVE".equals(group.getStatus()) ? "ACTIVE" : "INACTIVE")));
            }
        }
        if (type == null || "WORKFLOW".equals(type)) {
            // 团队工作流模板非本版本范围（接口文档 §14），无数据源，返回空列表
        }
        int size = clampLimit(limit);
        int offset = decodeCursor(cursor);
        boolean hasMore = offset + size < all.size();
        List<AgentAssignmentListItem> page = all.stream().skip(offset).limit(size).toList();
        String next = hasMore ? encodeCursor(offset + size) : null;
        return new PagedApiResponse<>(page, new PageInfo(next, hasMore), requestId);
    }

    /**
     * Agent 运行时摘要（契约 v1.8.0 §20，成员 B B06）。
     */
    public AgentRuntimeSummary runtime(UUID projectId, UUID agentId, UUID actor) {
        projectAccess.requireProjectMember(projectId, actor);
        requireAgent(agentId);
        long activeRuns = taskRunMapper.selectCount(Wrappers.<TaskRunEntity>lambdaQuery()
                .eq(TaskRunEntity::getProjectId, projectId)
                .eq(TaskRunEntity::getAgentId, agentId)
                .in(TaskRunEntity::getStatus, ACTIVE_RUN_STATUSES));
        List<UUID> groupIds = groupAgentMapper.selectGroupIdsByAgent(projectId, agentId);
        long assignableGroups = requirementGroupMapper.selectCount(Wrappers.<RequirementGroupEntity>lambdaQuery()
                .eq(RequirementGroupEntity::getProjectId, projectId)
                .eq(RequirementGroupEntity::getStatus, "ACTIVE"));
        return new AgentRuntimeSummary(activeRuns > 0 ? "RUNNING" : "IDLE", activeRuns, null,
                new AgentRuntimeSummary.AssignmentUsage(
                        new AgentRuntimeSummary.AssignmentCount(groupIds.size(), assignableGroups),
                        new AgentRuntimeSummary.AssignmentCount(0, 0)),
                "PROJECT", "PROJECT");
    }

    private void requireAgent(UUID agentId) {
        if (agentMapper.selectById(agentId) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在");
        }
    }

    private AgentResponse toResponse(AgentEntity agent, boolean owner) {
        return new AgentResponse(agent.getId().toString(), agent.getName(), agent.getAvatar(), agent.getRole(),
                agent.getCapabilities(), owner ? agent.getPrompt() : null, agent.getVisibility(), agent.getStatus(),
                agent.getCreatedBy() == null ? null : agent.getCreatedBy().toString());
    }

    private int clampLimit(Integer limit) {
        int value = limit == null ? DEFAULT_LIMIT : limit;
        return Math.min(Math.max(value, 1), MAX_LIMIT);
    }

    private int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "分页游标非法");
        }
    }

    private String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Integer.toString(offset).getBytes(StandardCharsets.UTF_8));
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }
}
