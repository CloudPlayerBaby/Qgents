package qg.qgent.orchestration;

/**
 * 专职 Agent 契约：接收结构化输入，返回结构化结果。
 * Agent 之间不直接通信，所有状态经 Orchestrator 以 AgentRunOutcome 路由。
 */
public interface Agent {
    AgentRunOutcome run(AgentInput input);
}
