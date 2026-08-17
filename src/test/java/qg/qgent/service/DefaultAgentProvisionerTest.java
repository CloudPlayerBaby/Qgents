package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.TeamEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.TeamMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 团队默认 Agent 预置契约：建团队事务内补齐 4 工作角色 + 编排助手、幂等跳过、
 * 并发撞唯一索引回查兜底、团队 ID 重载查找 Owner。
 */
class DefaultAgentProvisionerTest {

    private static final UUID TEAM = UUID.randomUUID();
    private static final UUID OWNER = UUID.randomUUID();
    private static final List<String> WORKER_ROLES = List.of("PLANNER", "DEVELOPER", "TESTER", "REVIEWER");

    private final TeamMapper teamMapper = mock(TeamMapper.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final OrchestratorAgentService orchestratorAgents = mock(OrchestratorAgentService.class);
    private final DefaultAgentProvisioner provisioner =
            new DefaultAgentProvisioner(teamMapper, agentMapper, orchestratorAgents);

    @Test
    void ensureForTeamCreatesAllWorkerRolesPlusOrchestrator() {
        when(agentMapper.selectList(any())).thenReturn(List.of());

        provisioner.ensureForTeam(TEAM, OWNER);

        ArgumentCaptor<AgentEntity> captor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentMapper, org.mockito.Mockito.times(4)).insert(captor.capture());
        verify(orchestratorAgents).ensureForTeam(TEAM, OWNER);
        assertEquals(WORKER_ROLES, captor.getAllValues().stream()
                .map(AgentEntity::getRole).toList());
        for (AgentEntity inserted : captor.getAllValues()) {
            assertEquals(TEAM, inserted.getTeamId());
            assertEquals(OWNER, inserted.getCreatedBy());
            assertEquals("TEAM", inserted.getVisibility());
            assertEquals("ACTIVE", inserted.getStatus());
            assertTrue(inserted.getIsDefault());
        }
    }

    @Test
    void ensureForTeamSkipsExistingDefaultAgents() {
        AgentEntity existing = agent("DEVELOPER");
        existing.setIsDefault(true);
        when(agentMapper.selectList(any())).thenReturn(List.of(existing));

        provisioner.ensureForTeam(TEAM, OWNER);

        verify(agentMapper, never()).insert(any(AgentEntity.class));
        verify(orchestratorAgents).ensureForTeam(TEAM, OWNER);
    }

    @Test
    void ensureForTeamSkipsLegacyDefaultWithoutFlag() {
        // 迁移前存量默认 Agent：is_default 未打标，命中已存在记录即跳过、不重复创建
        AgentEntity legacy = agent("DEVELOPER");
        legacy.setIsDefault(null);
        when(agentMapper.selectList(any())).thenReturn(List.of(legacy));

        provisioner.ensureForTeam(TEAM, OWNER);

        verify(agentMapper, never()).insert(any(AgentEntity.class));
    }

    @Test
    void duplicateKeyConflictFallsBackToExistingRecord() {
        // 每个角色：存在性检查返回空 → insert 撞唯一索引 → 回查返回已存在记录
        List<AgentEntity> empty = List.of();
        List<AgentEntity> existing = List.of(agent("PLANNER"));
        when(agentMapper.selectList(any())).thenReturn(empty, existing, empty, existing, empty, existing, empty,
                existing);
        doThrow(new DuplicateKeyException("dup")).when(agentMapper).insert(any(AgentEntity.class));

        provisioner.ensureForTeam(TEAM, OWNER);

        // 并发撞约束不抛异常，回查兜底后正常完成，ORCHESTRATOR 仍照常补齐
        verify(orchestratorAgents).ensureForTeam(TEAM, OWNER);
    }

    @Test
    void ensureForTeamByTeamIdLoadsOwnerFromTeam() {
        TeamEntity team = new TeamEntity();
        team.setId(TEAM);
        team.setOwnerUserId(OWNER);
        when(teamMapper.selectById(TEAM)).thenReturn(team);
        when(agentMapper.selectList(any())).thenReturn(List.of());

        provisioner.ensureForTeam(TEAM);

        ArgumentCaptor<AgentEntity> captor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(agentMapper, org.mockito.Mockito.times(4)).insert(captor.capture());
        verify(orchestratorAgents).ensureForTeam(TEAM, OWNER);
        captor.getAllValues().forEach(agent -> assertEquals(OWNER, agent.getCreatedBy()));
    }

    @Test
    void ensureForTeamByTeamIdSkipsMissingTeam() {
        when(teamMapper.selectById(TEAM)).thenReturn(null);

        provisioner.ensureForTeam(TEAM);

        verify(agentMapper, never()).insert(any(AgentEntity.class));
        verify(orchestratorAgents, never()).ensureForTeam(any(), any());
    }

    private AgentEntity agent(String role) {
        AgentEntity agent = new AgentEntity();
        agent.setId(UUID.randomUUID());
        agent.setTeamId(TEAM);
        agent.setCreatedBy(OWNER);
        agent.setRole(role);
        agent.setName("default-name-" + role);
        agent.setStatus("ACTIVE");
        agent.setVisibility("TEAM");
        return agent;
    }
}