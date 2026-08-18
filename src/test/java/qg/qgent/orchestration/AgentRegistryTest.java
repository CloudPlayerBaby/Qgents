package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;
import qg.qgent.entity.AgentEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.orchestration.agent.AgentToolRegistry;
import qg.qgent.orchestration.agent.CodingAgent;
import qg.qgent.orchestration.agent.ContextSearchProperties;
import qg.qgent.orchestration.agent.GenericCustomAgent;
import qg.qgent.orchestration.agent.PlanAgent;
import qg.qgent.orchestration.agent.ReviewAgent;
import qg.qgent.orchestration.agent.TestAgent;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.service.ContextService;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 运行时注册表解析测试：assignedAgentId==null 按角色回退内置 Agent；非空则查实体——团队默认
 * Agent（isDefault=true）复用对应内置 Agent 类，自定义 Agent 包装为 {@link GenericCustomAgent}；
 * 实体缺失或内置角色未知返回空 Optional（调用方「缺 Agent 不硬跑」）。
 */
class AgentRegistryTest {

    private final PlanAgent planAgent = mock(PlanAgent.class);
    private final CodingAgent codingAgent = mock(CodingAgent.class);
    private final TestAgent testAgent = mock(TestAgent.class);
    private final ReviewAgent reviewAgent = mock(ReviewAgent.class);
    private final AgentMapper agentMapper = mock(AgentMapper.class);
    private final LlmClient llm = mock(LlmClient.class);
    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
    private final AgentToolRegistry toolRegistry = mock(AgentToolRegistry.class);
    private final ContextService contextService = mock(ContextService.class);
    private final ContextSearchProperties contextSearchProperties = new ContextSearchProperties(10);
    private final AgentRegistry registry = new AgentRegistry(planAgent, codingAgent, testAgent, reviewAgent,
            agentMapper, toolRegistry, llm, codeAccess, writer, contextService, contextSearchProperties);

    @Test
    void nullAgentIdFallsBackToBuiltinByRole() {
        assertThat(registry.resolve(null, "PLANNER")).containsSame(planAgent);
        assertThat(registry.resolve(null, "DEVELOPER")).containsSame(codingAgent);
        assertThat(registry.resolve(null, "TESTER")).containsSame(testAgent);
        assertThat(registry.resolve(null, "REVIEWER")).containsSame(reviewAgent);
    }

    @Test
    void unknownBuiltinRoleResolvesEmpty() {
        assertThat(registry.resolve(null, "SECURITY")).isEmpty();
        assertThat(registry.resolve(null, null)).isEmpty();
    }

    @Test
    void customEntityWrapsGenericCustomAgent() {
        AgentEntity entity = new AgentEntity();
        entity.setId(UUID.randomUUID());
        entity.setRole("SECURITY");
        entity.setStatus("ACTIVE");
        entity.setPrompt("do the thing");
        when(agentMapper.selectById(entity.getId())).thenReturn(entity);

        Optional<Agent> resolved = registry.resolve(entity.getId(), "SECURITY");

        assertThat(resolved).isPresent();
        assertThat(resolved.get()).isInstanceOf(GenericCustomAgent.class);
    }

    @Test
    void defaultEntityReusesBuiltinAgent() {
        for (String role : new String[]{"PLANNER", "DEVELOPER", "TESTER", "REVIEWER"}) {
            AgentEntity entity = new AgentEntity();
            entity.setId(UUID.randomUUID());
            entity.setRole(role);
            entity.setStatus("ACTIVE");
            entity.setIsDefault(true);
            when(agentMapper.selectById(entity.getId())).thenReturn(entity);

            Optional<Agent> resolved = registry.resolve(entity.getId(), role);

            assertThat(resolved).as("default agent for role %s", role).isPresent();
            assertThat(resolved.get()).as("default agent for role %s", role)
                    .isInstanceOf(mappedBuiltinType(role));
        }
    }

    @Test
    void defaultEntityWithUnknownRoleFallsBackToGenericCustomAgent() {
        AgentEntity entity = new AgentEntity();
        entity.setId(UUID.randomUUID());
        entity.setRole("SECURITY");
        entity.setStatus("ACTIVE");
        entity.setIsDefault(true);
        entity.setPrompt("default spec");
        when(agentMapper.selectById(entity.getId())).thenReturn(entity);

        Optional<Agent> resolved = registry.resolve(entity.getId(), "SECURITY");

        assertThat(resolved).isPresent();
        assertThat(resolved.get()).isInstanceOf(GenericCustomAgent.class);
    }

    private Class<?> mappedBuiltinType(String role) {
        return switch (role) {
            case "PLANNER" -> PlanAgent.class;
            case "DEVELOPER" -> CodingAgent.class;
            case "TESTER" -> TestAgent.class;
            case "REVIEWER" -> ReviewAgent.class;
            default -> throw new IllegalArgumentException("unexpected role " + role);
        };
    }

    @Test
    void missingEntityResolvesEmpty() {
        UUID missing = UUID.randomUUID();
        when(agentMapper.selectById(missing)).thenReturn(null);

        assertThat(registry.resolve(missing, "DEVELOPER")).isEmpty();
    }
}
