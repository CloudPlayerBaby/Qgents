package qg.qgent.orchestration.agent;

import qg.qgent.orchestration.Agent;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;

/**
 * 自定义 Agent 的装饰器：复用内置引擎（Plan/Coding/Test/Review Agent），同时把自定义 Agent 的
 * prompt 作为补充指引叠加到该轮系统提示中。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>只负责把 {@code overlayPrompt} 写入 {@link AgentInput#setAgentPrompt}，然后委托给内置
 *       delegate 执行；内置 Agent 会把 overlay 段拼接到各自系统提示末尾，但最终通过/失败仍由真实
 *       exit code、严重度策略、写证据与结构化校验等确定性门禁决定，overlay 不得覆盖这些门禁；</li>
 *   <li>delegate 为无状态单例，{@link PromptBoundAgent} 本身也不持有可变状态，可安全并发复用；</li>
 *   <li>overlay 为空时透传执行，行为与直接使用内置 Agent 一致。</li>
 * </ul>
 */
public class PromptBoundAgent implements Agent {

    private final Agent delegate;
    private final String overlayPrompt;

    public PromptBoundAgent(Agent delegate, String overlayPrompt) {
        this.delegate = delegate;
        this.overlayPrompt = overlayPrompt;
    }

    @Override
    public AgentRunOutcome run(AgentInput input) {
        if (overlayPrompt != null && !overlayPrompt.isBlank()) {
            input.setAgentPrompt(overlayPrompt);
        }
        return delegate.run(input);
    }
}
