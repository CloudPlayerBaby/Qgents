package qg.qgent.orchestration;

import lombok.Data;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;

/**
 * Agent 执行的结构化输出，按相位携带对应的结果对象。
 * outcome 由状态机消费以决定下一步；结果对象供 Orchestrator 路由为下一 Agent 的输入。
 */
@Data
public class AgentRunOutcome {
    private OrchestrationPhase phase;
    private RunOutcome outcome;
    private PlanResult planResult;
    private CodingResult codingResult;
    private TestResult testResult;
    private ReviewResult reviewResult;
    /** 结果摘要或失败原因（已脱敏）。 */
    private String message;
}
