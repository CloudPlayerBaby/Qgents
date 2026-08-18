package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.ProjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调度 Agent 契约：候选池查询（团队 + 角色 + ACTIVE + 可见性）与决策委托，
 * 是后端唯一的「按 step 挑选 Agent」入口。
 */
class AgentDispatcherTest {

    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final AgentMatchDecider decider = mock(AgentMatchDecider.class);
    private final AgentDispatcher dispatcher = new AgentDispatcher(projects, agents, decider);

    @Test
    void dispatchQueriesTeamCandidatesAndDelegatesDecision() {
        TaskEntity task = task();
        ProjectEntity project = new ProjectEntity();
        project.setId(task.getProjectId());
        project.setTeamId(UUID.randomUUID());
        AgentEntity developer = agent("DEVELOPER");
        AgentEntity chosen = agent("DEVELOPER");
        chosen.setId(UUID.randomUUID());
        when(projects.selectById(task.getProjectId())).thenReturn(project);
        when(agents.selectList(any())).thenReturn(List.of(developer));
        when(decider.decide(eq("DEVELOPER"), any(), eq(task.getCreatedBy()),
                eq(List.of("java")), isNull())).thenReturn(Optional.of(chosen));

        Optional<AgentEntity> result = dispatcher.dispatch(task, "DEVELOPER", List.of("java"));

        assertEquals(chosen.getId(), result.get().getId());
        verify(decider).decide(eq("DEVELOPER"), eq(List.of(developer)), eq(task.getCreatedBy()),
                eq(List.of("java")), isNull());
    }

    @Test
    void dispatchPassesPlanSuggestedAgentIdAsPrior() {
        TaskEntity task = task();
        ProjectEntity project = new ProjectEntity();
        project.setId(task.getProjectId());
        project.setTeamId(UUID.randomUUID());
        AgentEntity developer = agent("DEVELOPER");
        UUID suggested = developer.getId();
        when(projects.selectById(task.getProjectId())).thenReturn(project);
        when(agents.selectList(any())).thenReturn(List.of(developer));
        when(decider.decide(eq("DEVELOPER"), any(), eq(task.getCreatedBy()),
                eq(List.of("java")), eq(suggested))).thenReturn(Optional.of(developer));

        Optional<AgentEntity> result = dispatcher.dispatch(task, "DEVELOPER", List.of("java"), suggested);

        assertEquals(suggested, result.get().getId());
        verify(decider).decide(eq("DEVELOPER"), eq(List.of(developer)), eq(task.getCreatedBy()),
                eq(List.of("java")), eq(suggested));
    }

    @Test
    void listTeamCandidatesReturnsAllVisibleAgentsForTeam() {
        TaskEntity task = task();
        ProjectEntity project = new ProjectEntity();
        project.setId(task.getProjectId());
        project.setTeamId(UUID.randomUUID());
        AgentEntity developer = agent("DEVELOPER");
        AgentEntity reviewer = agent("REVIEWER");
        when(projects.selectById(task.getProjectId())).thenReturn(project);
        when(agents.selectList(any())).thenReturn(List.of(developer, reviewer));

        List<AgentEntity> pool = dispatcher.listTeamCandidates(task.getProjectId(), task.getCreatedBy());

        assertThat(pool).containsExactlyInAnyOrder(developer, reviewer);
        verify(decider, never()).decide(any(), any(), any(), any(), any());
    }

    @Test
    void listTeamCandidatesReturnsEmptyWhenProjectHasNoTeam() {
        TaskEntity task = task();
        ProjectEntity project = new ProjectEntity();
        project.setId(task.getProjectId());
        when(projects.selectById(task.getProjectId())).thenReturn(project);

        assertThat(dispatcher.listTeamCandidates(task.getProjectId(), task.getCreatedBy())).isEmpty();
        verify(agents, never()).selectList(any());
    }

    @Test
    void listTeamCandidatesReturnsEmptyForMissingFields() {
        assertThat(dispatcher.listTeamCandidates(null, UUID.randomUUID())).isEmpty();
        assertThat(dispatcher.listTeamCandidates(UUID.randomUUID(), null)).isEmpty();
    }

    @Test
    void dispatchReturnsEmptyWhenProjectHasNoTeam() {
        TaskEntity task = task();
        ProjectEntity project = new ProjectEntity();
        project.setId(task.getProjectId());
        when(projects.selectById(task.getProjectId())).thenReturn(project);

        assertTrue(dispatcher.dispatch(task, "DEVELOPER", List.of()).isEmpty());
        verify(agents, never()).selectList(any());
        verify(decider, never()).decide(any(), any(), any(), any(), any());
    }

    @Test
    void dispatchReturnsEmptyWhenNoCandidates() {
        TaskEntity task = task();
        ProjectEntity project = new ProjectEntity();
        project.setId(task.getProjectId());
        project.setTeamId(UUID.randomUUID());
        when(projects.selectById(task.getProjectId())).thenReturn(project);
        when(agents.selectList(any())).thenReturn(List.of());
        when(decider.decide(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        assertTrue(dispatcher.dispatch(task, "DEVELOPER", List.of()).isEmpty());
    }

    @Test
    void dispatchReturnsEmptyForMissingTaskFields() {
        TaskEntity task = task();
        task.setProjectId(null);

        assertTrue(dispatcher.dispatch(task, "DEVELOPER", List.of()).isEmpty());
        verify(projects, never()).selectById(any());
        verify(agents, never()).selectList(any());
        verify(decider, never()).decide(any(), any(), any(), any(), any());
    }

    private TaskEntity task() {
        TaskEntity task = new TaskEntity();
        task.setId(UUID.randomUUID());
        task.setProjectId(UUID.randomUUID());
        task.setCreatedBy(UUID.randomUUID());
        return task;
    }

    private AgentEntity agent(String role) {
        AgentEntity agent = new AgentEntity();
        agent.setId(UUID.randomUUID());
        agent.setRole(role);
        agent.setVisibility("TEAM");
        agent.setStatus("ACTIVE");
        return agent;
    }
}