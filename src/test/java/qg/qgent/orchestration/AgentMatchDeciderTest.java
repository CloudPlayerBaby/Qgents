package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.entity.AgentEntity;
import qg.qgent.orchestration.llm.LlmClient;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent 选用决策器测试：role + description 丢给决策 Agent（LLM）判断，LLM 失败/非法输出走
 * 确定性兜底（个人 PRIVATE 优先 → 名称序）。
 */
class AgentMatchDeciderTest {

    private final UUID creatorId = UUID.randomUUID();
    private final LlmClient llm = mock(LlmClient.class);
    private final AgentMatchDecider decider = new AgentMatchDecider(llm);

    private AgentEntity agent(UUID id, String name, String description, String visibility) {
        AgentEntity agent = new AgentEntity();
        agent.setId(id);
        agent.setName(name);
        agent.setDescription(description);
        agent.setRole("DEVELOPER");
        agent.setVisibility(visibility);
        return agent;
    }

    private AgentEntity team(UUID id, String name, String description) {
        return agent(id, name, description, "TEAM");
    }

    private AgentEntity privateAgent(UUID id, String name, String description) {
        return agent(id, name, description, "PRIVATE");
    }

    @Test
    void emptyOrNullCandidatesReturnEmpty() {
        assertThat(decider.decide("DEVELOPER", null, creatorId, null)).isEmpty();
        assertThat(decider.decide("DEVELOPER", List.of(), creatorId, null)).isEmpty();
    }

    @Test
    void singleCandidateReturnsWithoutLlmCall() {
        AgentEntity only = team(UUID.randomUUID(), "唯一开发", "负责写代码");

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(only), creatorId, null);

        assertThat(best).containsSame(only);
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void llmChoiceInsidePoolIsAdopted() {
        AgentEntity a = team(UUID.randomUUID(), "A", "通用开发");
        AgentEntity b = team(UUID.randomUUID(), "B", "Java 专家，负责后端实现");
        when(llm.complete(anyString(), anyString())).thenReturn(b.getId().toString());

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(a, b), creatorId, null);

        assertThat(best).containsSame(b);
    }

    @Test
    void llmChoiceWithCodeFenceIsParsed() {
        AgentEntity a = team(UUID.randomUUID(), "A", "通用开发");
        AgentEntity b = team(UUID.randomUUID(), "B", "Java 专家");
        when(llm.complete(anyString(), anyString())).thenReturn("```\n" + b.getId() + "\n```");

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(a, b), creatorId, null);

        assertThat(best).containsSame(b);
    }

    @Test
    void noneFallsBackDeterministically() {
        AgentEntity personal = privateAgent(UUID.randomUUID(), "个人Java", "java 后端");
        AgentEntity t = team(UUID.randomUUID(), "团队开发", "通用开发");
        when(llm.complete(anyString(), anyString())).thenReturn("NONE");

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(t, personal), creatorId, null);

        assertThat(best).containsSame(personal);
    }

    @Test
    void garbageOutputFallsBackDeterministically() {
        AgentEntity a = team(UUID.randomUUID(), "A", "通用开发");
        AgentEntity b = team(UUID.randomUUID(), "B", "通用开发");
        when(llm.complete(anyString(), anyString())).thenReturn("this is not an agent id");

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(b, a), creatorId, null);

        assertThat(best).containsSame(a);
    }

    @Test
    void idOutsidePoolIsNotTrusted() {
        AgentEntity a = team(UUID.randomUUID(), "A", "通用开发");
        AgentEntity b = team(UUID.randomUUID(), "B", "通用开发");
        when(llm.complete(anyString(), anyString())).thenReturn(UUID.randomUUID().toString());

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(a, b), creatorId, null);

        assertThat(best).isPresent();
        assertThat(best.get()).isSameAs(a);
    }

    @Test
    void llmFailureFallsBackPreferringPrivateThenName() {
        AgentEntity t = team(UUID.randomUUID(), "B团队", "通用开发");
        AgentEntity p = privateAgent(UUID.randomUUID(), "A个人", "通用开发");
        when(llm.complete(anyString(), anyString())).thenThrow(new IllegalStateException("llm down"));

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(t, p), creatorId, null);

        assertThat(best).containsSame(p);
    }

    @Test
    void fallbackPreferPrivateOverTeamThenName() {
        AgentEntity tA = team(UUID.randomUUID(), "A团队", "通用开发");
        AgentEntity pB = privateAgent(UUID.randomUUID(), "B个人", "通用开发");
        when(llm.complete(anyString(), anyString())).thenReturn(null);

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(tA, pB), creatorId, null);

        assertThat(best).containsSame(pB);
    }

    @Test
    void stepRequirementsArePassedToDecisionAgent() {
        AgentEntity a = team(UUID.randomUUID(), "A", "通用开发");
        AgentEntity b = team(UUID.randomUUID(), "B", "Java 后端专家");
        when(llm.complete(anyString(), anyString())).thenReturn(b.getId().toString());

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(a, b), creatorId,
                List.of("java", "spring"));

        assertThat(best).containsSame(b);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(anyString(), captor.capture());
        assertThat(captor.getValue()).contains("步骤能力要求：java、spring");
    }

    @Test
    void suggestedAgentInsidePoolIsAdoptedWithoutLlmCall() {
        AgentEntity a = team(UUID.randomUUID(), "A", "通用开发");
        AgentEntity b = team(UUID.randomUUID(), "B", "Java 后端专家");

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(a, b), creatorId, null, b.getId());

        assertThat(best).containsSame(b);
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void suggestedAgentAdoptedEvenWhenPoolHasSingleCandidate() {
        AgentEntity only = team(UUID.randomUUID(), "唯一开发", "负责写代码");

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(only), creatorId, null, only.getId());

        assertThat(best).containsSame(only);
        verify(llm, never()).complete(anyString(), anyString());
    }

    @Test
    void suggestedAgentOutsidePoolFallsBackToLlmDecision() {
        AgentEntity a = team(UUID.randomUUID(), "A", "通用开发");
        AgentEntity b = team(UUID.randomUUID(), "B", "Java 后端专家");
        when(llm.complete(anyString(), anyString())).thenReturn(a.getId().toString());

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(a, b), creatorId, null,
                UUID.randomUUID());

        assertThat(best).containsSame(a);
        verify(llm).complete(anyString(), anyString());
    }

    @Test
    void suggestedAgentOutsidePoolAndLlmFailureFallsBackDeterministically() {
        AgentEntity personal = privateAgent(UUID.randomUUID(), "个人Java", "java 后端");
        AgentEntity t = team(UUID.randomUUID(), "团队开发", "通用开发");
        when(llm.complete(anyString(), anyString())).thenThrow(new IllegalStateException("llm down"));

        Optional<AgentEntity> best = decider.decide("DEVELOPER", List.of(t, personal), creatorId, null,
                UUID.randomUUID());

        assertThat(best).containsSame(personal);
    }
}