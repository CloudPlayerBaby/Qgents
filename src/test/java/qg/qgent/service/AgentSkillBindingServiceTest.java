package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import qg.qgent.api.ApiException;
import qg.qgent.dto.AgentSkillBindingsRequest;
import qg.qgent.dto.AgentSkillBindingsResponse;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.SkillEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.AgentSkillBindingMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.SkillMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Agent-Skill 绑定授权、归属与状态规则测试。 */
class AgentSkillBindingServiceTest {
    private final AgentSkillBindingMapper bindings = mock(AgentSkillBindingMapper.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final SkillMapper skills = mock(SkillMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final AgentSkillBindingService service = new AgentSkillBindingService(bindings, agents, skills,
            projects, access);

    @Test
    void replaceBindsSkillsAndReturnsPersistedSet() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID agentId = UUID.randomUUID(), skillId = UUID.randomUUID();
        ProjectEntity project = project(projectId);
        AgentEntity agent = agent(agentId, project.getTeamId(), actor, "ACTIVE");
        SkillEntity skill = skill(skillId, projectId, actor, "PROJECT_SHARED", "PUBLISHED");
        when(projects.selectById(projectId)).thenReturn(project);
        when(agents.selectById(agentId)).thenReturn(agent);
        when(skills.selectById(skillId)).thenReturn(skill);
        when(bindings.selectSkillIds(projectId, agentId)).thenReturn(List.of(skillId));
        when(skills.selectBatchIds(List.of(skillId))).thenReturn(List.of(skill));

        AgentSkillBindingsResponse response = service.replace(projectId, agentId, actor,
                request(skillId.toString()));

        verify(bindings).deleteByAgent(projectId, agentId);
        verify(bindings).insertBinding(projectId, agentId, skillId, actor);
        assertEquals(agentId.toString(), response.getAgentId());
        assertEquals(List.of(skillId.toString()), response.getSkillIds());
        assertEquals("PUBLISHED", response.getSkills().getFirst().getStatus());
    }

    @Test
    void replaceWithEmptyListClearsBindings() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        ProjectEntity project = project(projectId);
        when(projects.selectById(projectId)).thenReturn(project);
        when(agents.selectById(agentId)).thenReturn(agent(agentId, project.getTeamId(), actor, "ACTIVE"));
        when(bindings.selectSkillIds(projectId, agentId)).thenReturn(List.of());

        service.replace(projectId, agentId, actor, request());

        verify(bindings).deleteByAgent(projectId, agentId);
        verify(bindings, never()).insertBinding(any(), any(), any(), any());
        assertTrue(service.get(projectId, agentId, actor).getSkillIds().isEmpty());
    }

    @Test
    void replaceRejectsNonOwnerWithoutAdmin() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), other = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        ProjectEntity project = project(projectId);
        when(projects.selectById(projectId)).thenReturn(project);
        when(agents.selectById(agentId)).thenReturn(agent(agentId, project.getTeamId(), other, "ACTIVE"));
        doThrow(new ApiException(HttpStatus.FORBIDDEN, "PROJECT_ADMIN_REQUIRED", "需要项目 Admin 权限"))
                .when(access).requireProjectAdmin(projectId, actor);

        ApiException error = assertThrows(ApiException.class,
                () -> service.replace(projectId, agentId, actor, request()));

        assertEquals("AGENT_BINDING_FORBIDDEN", error.code());
        verify(bindings, never()).deleteByAgent(any(), any());
    }

    @Test
    void replaceRejectsAgentOutsideProjectTeam() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        ProjectEntity project = project(projectId);
        when(projects.selectById(projectId)).thenReturn(project);
        when(agents.selectById(agentId)).thenReturn(agent(agentId, UUID.randomUUID(), actor, "ACTIVE"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.replace(projectId, agentId, actor, request()));

        assertEquals("AGENT_NOT_IN_PROJECT_TEAM", error.code());
    }

    @Test
    void replaceRejectsInactiveAgent() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        ProjectEntity project = project(projectId);
        when(projects.selectById(projectId)).thenReturn(project);
        when(agents.selectById(agentId)).thenReturn(agent(agentId, project.getTeamId(), actor, "DISABLED"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.replace(projectId, agentId, actor, request()));

        assertEquals("AGENT_NOT_ACTIVE", error.code());
    }

    @Test
    void replaceRejectsSkillFromAnotherProject() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID agentId = UUID.randomUUID(), skillId = UUID.randomUUID();
        ProjectEntity project = project(projectId);
        when(projects.selectById(projectId)).thenReturn(project);
        when(agents.selectById(agentId)).thenReturn(agent(agentId, project.getTeamId(), actor, "ACTIVE"));
        when(skills.selectById(skillId)).thenReturn(skill(skillId, UUID.randomUUID(), actor, "PROJECT_SHARED",
                "PUBLISHED"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.replace(projectId, agentId, actor, request(skillId.toString())));

        assertEquals("SKILL_NOT_IN_PROJECT", error.code());
    }

    @Test
    void replaceRejectsUnbindableSkill() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), other = UUID.randomUUID();
        UUID agentId = UUID.randomUUID(), skillId = UUID.randomUUID();
        ProjectEntity project = project(projectId);
        when(projects.selectById(projectId)).thenReturn(project);
        when(agents.selectById(agentId)).thenReturn(agent(agentId, project.getTeamId(), actor, "ACTIVE"));
        // 他人 PRIVATE Skill 不可绑定
        when(skills.selectById(skillId)).thenReturn(skill(skillId, projectId, other, "PRIVATE", "PUBLISHED"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.replace(projectId, agentId, actor, request(skillId.toString())));

        assertEquals("SKILL_NOT_BINDABLE", error.code());
    }

    @Test
    void replaceRejectsMissingSkill() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID agentId = UUID.randomUUID(), skillId = UUID.randomUUID();
        ProjectEntity project = project(projectId);
        when(projects.selectById(projectId)).thenReturn(project);
        when(agents.selectById(agentId)).thenReturn(agent(agentId, project.getTeamId(), actor, "ACTIVE"));
        when(skills.selectById(skillId)).thenReturn(null);

        ApiException error = assertThrows(ApiException.class,
                () -> service.replace(projectId, agentId, actor, request(skillId.toString())));

        assertEquals("SKILL_NOT_FOUND", error.code());
    }

    @Test
    void replaceRejectsDuplicateSkillIds() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID();
        UUID agentId = UUID.randomUUID(), skillId = UUID.randomUUID();
        ProjectEntity project = project(projectId);
        when(projects.selectById(projectId)).thenReturn(project);
        when(agents.selectById(agentId)).thenReturn(agent(agentId, project.getTeamId(), actor, "ACTIVE"));
        when(skills.selectById(skillId)).thenReturn(skill(skillId, projectId, actor, "PROJECT_SHARED", "PUBLISHED"));

        ApiException error = assertThrows(ApiException.class,
                () -> service.replace(projectId, agentId, actor,
                        request(skillId.toString(), skillId.toString())));

        assertEquals("AGENT_SKILL_DUPLICATE", error.code());
        verify(bindings, never()).deleteByAgent(any(), any());
    }

    private ProjectEntity project(UUID projectId) {
        ProjectEntity project = new ProjectEntity();
        project.setId(projectId);
        project.setTeamId(UUID.randomUUID());
        project.setStatus("ACTIVE");
        return project;
    }

    private AgentEntity agent(UUID agentId, UUID teamId, UUID createdBy, String status) {
        AgentEntity agent = new AgentEntity();
        agent.setId(agentId);
        agent.setTeamId(teamId);
        agent.setCreatedBy(createdBy);
        agent.setName("dev-agent");
        agent.setRole("DEVELOPER");
        agent.setStatus(status);
        return agent;
    }

    private SkillEntity skill(UUID skillId, UUID projectId, UUID createdBy, String visibility, String status) {
        SkillEntity skill = new SkillEntity();
        skill.setId(skillId);
        skill.setProjectId(projectId);
        skill.setCreatedBy(createdBy);
        skill.setName("compile-check");
        skill.setVisibility(visibility);
        skill.setStatus(status);
        return skill;
    }

    private AgentSkillBindingsRequest request(String... skillIds) {
        AgentSkillBindingsRequest request = new AgentSkillBindingsRequest();
        request.setSkillIds(List.of(skillIds));
        return request;
    }
}
