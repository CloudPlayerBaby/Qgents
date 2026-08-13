package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.dto.AgentSkillBindingsRequest;
import qg.qgent.dto.AgentSkillBindingsResponse;
import qg.qgent.dto.SkillBindingItemResponse;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.SkillEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.AgentSkillBindingMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.SkillMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Agent-Skill 绑定服务（按项目隔离，PUT 全量替换语义）。
 * <p>
 * 绑定将项目内可用 Skill 装配到 Team 级 Agent，供前端展示与后续编排装配使用。
 * 授权与校验规则：
 * <ul>
 *   <li>403 AGENT_BINDING_FORBIDDEN：修改非本人创建的 PRIVATE Agent 且非 Project Admin。</li>
 *   <li>422 AGENT_NOT_IN_PROJECT_TEAM：Agent 不属于当前项目的 Team。</li>
 *   <li>422 AGENT_NOT_ACTIVE：Agent 未启用。</li>
 *   <li>404 SKILL_NOT_FOUND / 422 SKILL_NOT_IN_PROJECT：Skill 不存在或不属于当前项目。</li>
 *   <li>422 SKILL_NOT_BINDABLE：Skill 处于 ARCHIVED，或他人 PRIVATE Skill，或 PROJECT_SHARED 未 PUBLISHED。</li>
 *   <li>409 AGENT_SKILL_DUPLICATE：请求体 skillIds 存在重复，绑定集合无法确定。</li>
 * </ul>
 * PUT 本身幂等，无需 Idempotency-Key；空数组表示清空全部绑定。
 */
@Service
public class AgentSkillBindingService {

    private final AgentSkillBindingMapper bindingMapper;
    private final AgentMapper agentMapper;
    private final SkillMapper skillMapper;
    private final ProjectMapper projectMapper;
    private final ProjectAccessService access;

    public AgentSkillBindingService(AgentSkillBindingMapper bindingMapper, AgentMapper agentMapper,
            SkillMapper skillMapper, ProjectMapper projectMapper, ProjectAccessService access) {
        this.bindingMapper = bindingMapper;
        this.agentMapper = agentMapper;
        this.skillMapper = skillMapper;
        this.projectMapper = projectMapper;
        this.access = access;
    }

    /** 读取指定 Agent 在当前项目的绑定集（无需修改权限，项目成员即可读）。 */
    public AgentSkillBindingsResponse get(UUID projectId, UUID agentId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        return response(projectId, agentId);
    }

    /**
     * 全量替换指定 Agent 在当前项目的 Skill 绑定。
     *
     * @param projectId 项目 ID
     * @param agentId   Agent ID
     * @param actor     当前用户 ID
     * @param request   替换后的完整绑定集；空数组清空
     * @return 替换后的绑定响应
     */
    @Transactional
    public AgentSkillBindingsResponse replace(UUID projectId, UUID agentId, UUID actor,
            AgentSkillBindingsRequest request) {
        access.requireProjectMember(projectId, actor);
        ProjectEntity project = requireProject(projectId);
        AgentEntity agent = requireAgent(agentId);
        requireOwner(agent, projectId, actor);
        requireBindableAgent(project, agent);
        List<UUID> skillIds = validateSkills(projectId, actor, request.getSkillIds());
        bindingMapper.deleteByAgent(projectId, agentId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (UUID skillId : skillIds) {
            bindingMapper.insertBinding(projectId, agentId, skillId, actor);
        }
        return response(projectId, agentId);
    }

    private List<UUID> validateSkills(UUID projectId, UUID actor, List<String> rawIds) {
        List<UUID> ids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : rawIds) {
            if (raw == null || raw.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SKILL_ID", "Skill ID 不能为空");
            }
            String trimmed = raw.trim();
            if (!seen.add(trimmed)) {
                throw new ApiException(HttpStatus.CONFLICT, "AGENT_SKILL_DUPLICATE", "请求体 Skill ID 重复");
            }
            UUID skillId;
            try {
                skillId = UUID.fromString(trimmed);
            } catch (IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SKILL_ID", "Skill ID 格式非法");
            }
            SkillEntity skill = skillMapper.selectById(skillId);
            if (skill == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "SKILL_NOT_FOUND", "Skill 不存在或不可见");
            }
            if (!projectId.equals(skill.getProjectId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SKILL_NOT_IN_PROJECT",
                        "Skill 不属于当前项目");
            }
            if (!bindable(skill, actor)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SKILL_NOT_BINDABLE",
                        "Skill 不可绑定：已归档、他人私有或尚未发布");
            }
            ids.add(skillId);
        }
        return ids;
    }

    /** 仅 Agent 创建者或 Project Admin 可修改绑定；TEAM 共享 Agent 视为无创建者，需 Admin。 */
    private void requireOwner(AgentEntity agent, UUID projectId, UUID actor) {
        if (agent.getCreatedBy() != null && agent.getCreatedBy().equals(actor)) {
            return;
        }
        try {
            access.requireProjectAdmin(projectId, actor);
        } catch (ApiException e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "AGENT_BINDING_FORBIDDEN",
                    "仅 Agent 创建者或 Project Admin 可修改绑定");
        }
    }

    private void requireBindableAgent(ProjectEntity project, AgentEntity agent) {
        if (!project.getTeamId().equals(agent.getTeamId())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_NOT_IN_PROJECT_TEAM",
                    "Agent 不属于当前项目的 Team");
        }
        if (!"ACTIVE".equals(agent.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "AGENT_NOT_ACTIVE", "Agent 未启用");
        }
    }

    /** PROJECT_SHARED 需已 PUBLISHED；PRIVATE 仅本人可用且非 ARCHIVED。 */
    private boolean bindable(SkillEntity skill, UUID actor) {
        if ("ARCHIVED".equals(skill.getStatus())) {
            return false;
        }
        if ("PROJECT_SHARED".equals(skill.getVisibility())) {
            return "PUBLISHED".equals(skill.getStatus());
        }
        if ("PRIVATE".equals(skill.getVisibility())) {
            return actor.equals(skill.getCreatedBy());
        }
        return false;
    }

    private ProjectEntity requireProject(UUID projectId) {
        ProjectEntity project = projectMapper.selectById(projectId);
        if (project == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "项目不存在或不可见");
        }
        return project;
    }

    private AgentEntity requireAgent(UUID agentId) {
        AgentEntity agent = agentMapper.selectById(agentId);
        if (agent == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "Agent 不存在");
        }
        return agent;
    }

    private AgentSkillBindingsResponse response(UUID projectId, UUID agentId) {
        List<UUID> skillIds = bindingMapper.selectSkillIds(projectId, agentId);
        List<SkillBindingItemResponse> skills = skillIds.isEmpty() ? List.of()
                : skillMapper.selectBatchIds(skillIds).stream()
                        .sorted((a, b) -> Integer.compare(skillIds.indexOf(a.getId()), skillIds.indexOf(b.getId())))
                        .map(s -> new SkillBindingItemResponse(id(s.getId()), s.getName(), s.getVisibility(),
                                s.getStatus()))
                        .toList();
        return new AgentSkillBindingsResponse(id(agentId), skillIds.stream().map(this::id).toList(), skills,
                iso(LocalDateTime.now(ZoneOffset.UTC)));
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}
