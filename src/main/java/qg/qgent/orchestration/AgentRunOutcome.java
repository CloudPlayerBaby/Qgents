package qg.qgent.orchestration;

import lombok.Data;
import qg.qgent.orchestration.llm.LlmObservation;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;

import java.util.ArrayList;
import java.util.List;

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
    /**
     * 结果摘要或失败原因（已脱敏）。
     */
    private String message;
    /**
     * 稳定失败分类码（如 AGENT_RUN_TIMEOUT / ORPHANED_RUN_TIMEOUT），用于随 Run 产物摘要落库定位；
     * 仅失败且可分类时非空。
     */
    private String failureCode;
    /**
     * 本相位每次模型调用的脱敏观测（阶段 A）：随 Run 产物摘要落库，失败时可定位错误码。
     * 缺失时为 null（PLAN/LEGACY 或未执行模型调用），落库侧做空值兼容。
     */
    private List<LlmObservation> observations;

    /**
     * 追加一条观测；首次调用时惰性初始化列表。
     */
    public void addObservation(LlmObservation observation) {
        if (observation == null) {
            return;
        }
        if (observations == null) {
            observations = new ArrayList<>();
        }
        observations.add(observation);
    }
}
