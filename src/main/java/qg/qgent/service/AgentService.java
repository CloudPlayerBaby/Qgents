package qg.qgent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.AgentAssignmentListItem;
import qg.qgent.dto.AgentResponse;
import qg.qgent.dto.AgentRuntimeSummary;
import qg.qgent.dto.CreateAgentRequest;
import qg.qgent.dto.PageInfo;
import qg.qgent.dto.UpdateAgentRequest;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.RequirementGroupEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TeamEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.GroupAgentMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.RequirementGroupMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.TeamMapper;
import qg.qgent.mapper.TeamMemberMapper;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Team-scoped Agent visibility, lifecycle and read operations.
 */
@Service
public class AgentService {
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private static final List<String> ACTIVE_RUN_STATUSES = List.of("RUNNING", "QUEUED", "WAITING_INPUT",
            "WAITING_APPROVAL", "BLOCKED", "CANCELLING");
    /**
     * 角色标签白名单（契约 §11.1）：只是调度与工具权限的角色标签，不是客户端可自行扩大的权限。
     */
    private static final Set<String> ALLOWED_ROLES = Set.of("ORCHESTRATOR", "PLANNER", "DEVELOPER",
            "TESTER", "REVIEWER", "GENERAL");
    /**
     * Prompt 敏感信息拦截模式：命中即拒绝保存，防止凭据/私钥/宿主机敏感信息进入 Agent 上下文。
     */
    private static final List<Pattern> SENSITIVE_PROMPT_PATTERNS = List.of(
            Pattern.compile("-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("gh[pous]_[A-Za-z0-9]{20,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("sk-[A-Za-z0-9]{16,}"),
            Pattern.compile("xox[bap]-[0-9A-Za-z-]{10,}"),
            Pattern.compile("(id_rsa|id_ed25519|/etc/passwd|/home/[A-Za-z0-9_.-]+\\.ssh)"));

    private final AgentMapper agentMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final ProjectAccessService projectAccess;
    private final GroupAgentMapper groupAgentMapper;
    private final RequirementGroupMapper requirementGroupMapper;
    private final TaskRunMapper taskRunMapper;
    private final ProjectMapper projectMapper;
    private final TeamMapper teamMapper;

    public AgentService(AgentMapper agentMapper, TeamMemberMapper teamMemberMapper,
                        ProjectAccessService projectAccess, GroupAgentMapper groupAgentMapper,
                        RequirementGroupMapper requirementGroupMapper, TaskRunMapper taskRunMapper,
                        ProjectMapper projectMapper, TeamMapper teamMapper) {
        this.agentMapper = agentMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.projectAccess = projectAccess;
        this.groupAgentMapper = groupAgentMapper;
        this.requirementGroupMapper = requirementGroupMapper;
        this.taskRunMapper = taskRunMapper;
        this.projectMapper = projectMapper;
        this.teamMapper = teamMapper;
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
     * 获取单张 Agent 卡（前端需要）：团队可见性校验；projectId 可选，传了则额外校验
     * 该 Agent 属于此项目的 Team（项目上下文可见），并校验调用者是该项目成员。
     * PRIVATE Agent 仅创建者可见（prompt 仅创建者返回）。
     */
    public AgentResponse get(UUID teamId, UUID agentId, UUID actor, UUID projectId) {
        if (teamMemberMapper.selectByTeamAndUser(teamId, actor) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_RESOURCE_NOT_FOUND", "团队资源不存在或不可见");
        }
        if (projectId != null) {
            projectAccess.requireProjectMember(projectId, actor);
            qg.qgent.entity.ProjectEntity project = projectMapper.selectById(projectId);
            if (project == null || !teamId.equals(project.getTeamId())) {
                throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
            }
        }
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null || !teamId.equals(agent.getTeamId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在或不可见");
        }
        boolean owner = actor.equals(agent.getCreatedBy());
        if (!"TEAM".equals(agent.getVisibility()) && !owner) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在或不可见");
        }
        return toResponse(agent, owner);
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

    // ---------- 自定义 Agent 生命周期管理（契约 §11.1，接口补充 v2.0.3 §2-§6） ----------

    /**
     * 创建自定义 Agent：创建者只能创建自己所属 Team 的 Agent，创建成功后固定为
     * {@code visibility=PRIVATE}、{@code status=ACTIVE}、{@code isDefault=false}。
     */
    @Transactional
    public AgentResponse create(UUID actor, UUID teamId, CreateAgentRequest request) {
        requireTeamMember(teamId, actor);
        validateRole(request.getRole());
        validateName(request.getName());
        validateAvatar(request.getAvatar());
        validatePrompt(request.getPrompt());
        AgentEntity agent = new AgentEntity();
        agent.setId(UuidV7.next());
        agent.setTeamId(teamId);
        agent.setCreatedBy(actor);
        agent.setName(request.getName().trim());
        agent.setAvatar(blankToNull(request.getAvatar()));
        agent.setRole(request.getRole());
        agent.setDescription(blankToNull(request.getDescription()));
        agent.setPrompt(request.getPrompt());
        agent.setVisibility("PRIVATE");
        agent.setStatus("ACTIVE");
        agent.setIsDefault(false);
        agentMapper.insert(agent);
        return toResponse(agent, true);
    }

    /**
     * 编辑自定义 Agent：仅创建者可编辑，系统预置 Agent（isDefault=true）不可编辑。
     * 所有字段可选但至少一个；visibility/status/createdBy/isDefault 不允许客户端修改。
     * role 变更只影响后续新分配的 TaskStep，不改变已定型的 TaskStep.assignedAgentId。
     */
    @Transactional
    public AgentResponse update(UUID actor, UUID teamId, UUID agentId, UpdateAgentRequest request) {
        requireTeamMember(teamId, actor);
        AgentEntity agent = requireAgentInTeam(teamId, agentId);
        requireNotDefault(agent);
        requireEditable(agent, actor);
        if (isEmptyPatch(request)) {
            throw invalidRequest("请求体至少包含一个字段");
        }
        if (request.getName() != null) {
            validateName(request.getName());
            agent.setName(request.getName().trim());
        }
        if (request.getAvatar() != null) {
            validateAvatar(request.getAvatar());
            agent.setAvatar(blankToNull(request.getAvatar()));
        }
        if (request.getRole() != null) {
            validateRole(request.getRole());
            agent.setRole(request.getRole());
        }
        if (request.getDescription() != null) {
            agent.setDescription(blankToNull(request.getDescription()));
        }
        if (request.getPrompt() != null) {
            validatePrompt(request.getPrompt());
            agent.setPrompt(request.getPrompt());
        }
        agentMapper.updateById(agent);
        return toResponse(agent, true);
    }

    /**
     * 发布 Agent：PRIVATE + ACTIVE → TEAM + ACTIVE。仅创建者可发布；发布后团队成员可查询和使用。
     */
    @Transactional
    public AgentResponse publish(UUID actor, UUID teamId, UUID agentId) {
        requireTeamMember(teamId, actor);
        AgentEntity agent = requireAgentInTeam(teamId, agentId);
        requireNotDefault(agent);
        requireVisibleForManage(agent, actor);
        if (agent.getCreatedBy() == null || !agent.getCreatedBy().equals(actor)) {
            throw forbidden("仅 Agent 创建者可以发布");
        }
        if (!"ACTIVE".equals(agent.getStatus()) || !"PRIVATE".equals(agent.getVisibility())) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_STATE_CONFLICT",
                    "只有 PRIVATE 且 ACTIVE 的 Agent 可以发布");
        }
        agent.setVisibility("TEAM");
        agentMapper.updateById(agent);
        return toResponse(agent, true);
    }

    /**
     * 收回发布：TEAM + ACTIVE → PRIVATE + ACTIVE。仅创建者或 Team Owner 可收回；
     * 非创建者收回后 Agent 仍归原创建者所有。
     */
    @Transactional
    public AgentResponse unpublish(UUID actor, UUID teamId, UUID agentId) {
        requireTeamMember(teamId, actor);
        AgentEntity agent = requireAgentInTeam(teamId, agentId);
        requireNotDefault(agent);
        requireVisibleForManage(agent, actor);
        if (!canManagePublished(agent, actor, teamId)) {
            throw forbidden("仅 Agent 创建者或 Team Owner 可收回发布");
        }
        if (!"ACTIVE".equals(agent.getStatus()) || !"TEAM".equals(agent.getVisibility())) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_STATE_CONFLICT",
                    "只有 TEAM 且 ACTIVE 的 Agent 可以收回发布");
        }
        agent.setVisibility("PRIVATE");
        agentMapper.updateById(agent);
        return toResponse(agent, true);
    }

    /**
     * 归档 Agent：PRIVATE/TEAM + ACTIVE → ARCHIVED。仅创建者或 Team Owner 可归档。
     * 归档后不能绑定新 TaskStep；已运行 TaskRun 不受影响；历史记录与 Skill 关系保留。
     */
    @Transactional
    public AgentResponse archive(UUID actor, UUID teamId, UUID agentId) {
        requireTeamMember(teamId, actor);
        AgentEntity agent = requireAgentInTeam(teamId, agentId);
        requireNotDefault(agent);
        requireVisibleForManage(agent, actor);
        if (!canManagePublished(agent, actor, teamId)) {
            throw forbidden("仅 Agent 创建者或 Team Owner 可归档");
        }
        if ("ARCHIVED".equals(agent.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_STATE_CONFLICT", "Agent 已归档");
        }
        agent.setStatus("ARCHIVED");
        agentMapper.updateById(agent);
        return toResponse(agent, true);
    }

    private void requireTeamMember(UUID teamId, UUID actor) {
        if (teamMemberMapper.selectByTeamAndUser(teamId, actor) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "团队不存在或不可见");
        }
    }

    private AgentEntity requireAgentInTeam(UUID teamId, UUID agentId) {
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null || !teamId.equals(agent.getTeamId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在或不可见");
        }
        return agent;
    }

    private void requireNotDefault(AgentEntity agent) {
        if (Boolean.TRUE.equals(agent.getIsDefault())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_DEFAULT_IMMUTABLE",
                    "系统预置 Agent 不可编辑、发布、收回或归档");
        }
    }

    /**
     * 编辑权限：PRIVATE 仅创建者可见（非创建者按 404 处理）；TEAM 团队成员可见但仅创建者可编辑。
     */
    private void requireEditable(AgentEntity agent, UUID actor) {
        if (agent.getCreatedBy() != null && agent.getCreatedBy().equals(actor)) {
            return;
        }
        if ("PRIVATE".equals(agent.getVisibility())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在或不可见");
        }
        throw forbidden("仅 Agent 创建者可以编辑");
    }

    /**
     * 管理操作可见性：PRIVATE Agent 仅创建者可见，非创建者按 404 处理；
     * TEAM Agent 团队成员可见，操作权限由调用方另行校验。
     */
    private void requireVisibleForManage(AgentEntity agent, UUID actor) {
        if ("PRIVATE".equals(agent.getVisibility())
                && (agent.getCreatedBy() == null || !agent.getCreatedBy().equals(actor))) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在或不可见");
        }
    }

    /**
     * 发布后 Agent（TEAM）的收回/归档权限：创建者或 Team Owner（canonical owner）。
     */
    private boolean canManagePublished(AgentEntity agent, UUID actor, UUID teamId) {
        if (agent.getCreatedBy() != null && agent.getCreatedBy().equals(actor)) {
            return true;
        }
        TeamEntity team = teamMapper.selectById(teamId);
        return team != null && actor.equals(team.getOwnerUserId());
    }

    private boolean isEmptyPatch(UpdateAgentRequest request) {
        return request.getName() == null && request.getAvatar() == null && request.getRole() == null
                && request.getDescription() == null && request.getPrompt() == null;
    }

    private void validateRole(String role) {
        if (role == null || !ALLOWED_ROLES.contains(role)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_ROLE_INVALID",
                    "角色不在允许集合内");
        }
    }

    private void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw invalidRequest("name 去除首尾空白后非空");
        }
        if (name.trim().length() > 255) {
            throw invalidRequest("name 最长 255 个字符");
        }
    }

    private void validateAvatar(String avatar) {
        if (avatar == null || avatar.isBlank()) {
            return;
        }
        if (avatar.length() > 2048) {
            throw invalidRequest("avatar 最长 2048 个字符");
        }
        try {
            URI uri = new URI(avatar.trim());
            if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) {
                throw invalidRequest("avatar 必须是合法的 http/https URL");
            }
        } catch (URISyntaxException e) {
            throw invalidRequest("avatar 必须是合法的 http/https URL");
        }
    }

    private void validatePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw invalidRequest("prompt 不能为空");
        }
        if (prompt.length() > 20000) {
            throw invalidRequest("prompt 最长 20000 个字符");
        }
        for (Pattern pattern : SENSITIVE_PROMPT_PATTERNS) {
            if (pattern.matcher(prompt).find()) {
                throw invalidRequest("prompt 不得包含凭据、Token、私钥或宿主机敏感信息");
            }
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ApiException invalidRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "AGENT_INVALID_REQUEST", message);
    }

    private ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "AGENT_FORBIDDEN", message);
    }

    private AgentResponse toResponse(AgentEntity agent, boolean owner) {
        return new AgentResponse(agent.getId().toString(), agent.getName(), agent.getAvatar(), agent.getRole(),
                agent.getDescription(), owner ? agent.getPrompt() : null, agent.getVisibility(), agent.getStatus(),
                agent.getCreatedBy() == null ? null : agent.getCreatedBy().toString(),
                Boolean.TRUE.equals(agent.getIsDefault()));
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
