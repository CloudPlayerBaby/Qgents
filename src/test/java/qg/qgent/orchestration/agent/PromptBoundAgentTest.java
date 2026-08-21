package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.Agent;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PromptBoundAgent} 装饰器测试：非空 overlay 写入 {@link AgentInput#setAgentPrompt} 后委托
 * 内置 Agent；null/空白 overlay 透传执行，不写入 agentPrompt，行为与直接使用内置 Agent 一致。
 */
class PromptBoundAgentTest {

    @Test
    void nonBlankOverlayIsInjectedBeforeDelegating() {
        Agent delegate = mock(Agent.class);
        AgentRunOutcome outcome = new AgentRunOutcome();
        when(delegate.run(any())).thenReturn(outcome);
        PromptBoundAgent bound = new PromptBoundAgent(delegate, "custom planner spec");

        AgentInput input = new AgentInput();
        AgentRunOutcome result = bound.run(input);

        assertThat(input.getAgentPrompt()).isEqualTo("custom planner spec");
        verify(delegate).run(input);
        assertThat(result).isSameAs(outcome);
    }

    @Test
    void nullOverlayDelegatesWithoutWritingAgentPrompt() {
        Agent delegate = mock(Agent.class);
        when(delegate.run(any())).thenReturn(new AgentRunOutcome());
        PromptBoundAgent bound = new PromptBoundAgent(delegate, null);

        AgentInput input = new AgentInput();
        bound.run(input);

        assertThat(input.getAgentPrompt()).isNull();
        verify(delegate).run(input);
    }

    @Test
    void blankOverlayDelegatesWithoutWritingAgentPrompt() {
        Agent delegate = mock(Agent.class);
        when(delegate.run(any())).thenReturn(new AgentRunOutcome());
        PromptBoundAgent bound = new PromptBoundAgent(delegate, "  ");

        AgentInput input = new AgentInput();
        bound.run(input);

        assertThat(input.getAgentPrompt()).isNull();
        verify(delegate).run(input);
    }
}
