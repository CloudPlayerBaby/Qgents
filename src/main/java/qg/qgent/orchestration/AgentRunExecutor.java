package qg.qgent.orchestration;

import org.springframework.stereotype.Service;
import qg.qgent.orchestration.agent.CodingAgent;
import qg.qgent.orchestration.agent.PlanAgent;
import qg.qgent.orchestration.agent.ReviewAgent;
import qg.qgent.orchestration.agent.TestAgent;

import java.util.Map;

/**
 * 相位 → Agent 调度执行器：按当前相位选择专职 Agent 并执行，统一返回 AgentRunOutcome。
 * Phase 1 由 Mock Agent 返回固定合法结果；Phase 2 接入真实 LLM 调用与 Tool Calling。
 */
@Service
public class AgentRunExecutor {
    private final Map<OrchestrationPhase, Agent> agents;

    public AgentRunExecutor(PlanAgent planAgent, CodingAgent codingAgent, TestAgent testAgent,
            ReviewAgent reviewAgent) {
        this.agents = Map.of(
                OrchestrationPhase.PLAN, planAgent,
                OrchestrationPhase.CODING, codingAgent,
                OrchestrationPhase.TESTING, testAgent,
                OrchestrationPhase.REVIEWING, reviewAgent);
    }

    /** 执行指定相位对应的 Agent。 */
    public AgentRunOutcome execute(OrchestrationPhase phase, AgentInput input) {
        Agent agent = agents.get(phase);
        if (agent == null) {
            throw new IllegalStateException("No agent registered for phase " + phase);
        }
        return agent.run(input);
    }
}
