package qg.qgent.orchestration;

import org.springframework.stereotype.Component;

/**
 * 确定性状态机：依据当前相位、Agent 结果与循环计数决定下一步。
 * 纯逻辑、无 I/O、不调用 LLM；可独立单元测试，也可被同步/异步驱动复用。
 * <p>
 * 转移规则：
 * PLAN/CODING SUCCEEDED → 进入下一相位；质量失败 → Task FAILED（Plan/Coding 无修复循环）。
 * TESTING/REVIEWING SUCCEEDED → 进入下一相位 / Task SUCCESS。
 * TESTING 任一测试失败（FAILED/FAILED_QUALITY/TEST_FAILED）→ 进入 REVIEWING：Test 不判定任务
 * 是否失败，Review 是最终裁决，仅 BLOCKER/MAJOR 判失败（见 REVIEWING 分支）。
 * REVIEWING FAILED_QUALITY → 质量循环内回到 CODING，超限 Task FAILED；FAILED/TEST_FAILED → Task FAILED。
 * 任一相位 FAILED_INFRASTRUCTURE → 同相位重试（计数），超限 Task FAILED。
 * 任一相位 CANCELLED → Task CANCELLED。
 */
@Component
public class OrchestrationStateMachine {

    /**
     * 依据相位与结果决策下一步；会推进传入 counters 的循环计数。
     */
    public StateMachineDecision decide(OrchestrationPhase phase, RunOutcome outcome, OrchestrationCounters counters) {
        if (outcome != RunOutcome.FAILED_INFRASTRUCTURE) {
            counters.resetInfraRetries(phase);
        }
        return switch (phase) {
            case PLAN -> plan(phase, outcome, counters);
            case CODING -> coding(phase, outcome, counters);
            case TESTING -> testing(phase, outcome, counters);
            case REVIEWING -> reviewing(phase, outcome, counters);
        };
    }

    private StateMachineDecision plan(OrchestrationPhase phase, RunOutcome outcome, OrchestrationCounters counters) {
        return switch (outcome) {
            case SUCCEEDED -> StateMachineDecision.advance(OrchestrationPhase.CODING);
            case FAILED_INFRASTRUCTURE -> retryOrFail(phase, counters);
            case FAILED, FAILED_QUALITY, TEST_FAILED -> StateMachineDecision.failed();
            case CANCELLED -> StateMachineDecision.cancelled();
        };
    }

    private StateMachineDecision coding(OrchestrationPhase phase, RunOutcome outcome, OrchestrationCounters counters) {
        return switch (outcome) {
            case SUCCEEDED -> StateMachineDecision.advance(OrchestrationPhase.TESTING);
            case FAILED_INFRASTRUCTURE -> retryOrFail(phase, counters);
            case FAILED, FAILED_QUALITY, TEST_FAILED -> StateMachineDecision.failed();
            case CANCELLED -> StateMachineDecision.cancelled();
        };
    }

    private StateMachineDecision testing(OrchestrationPhase phase, RunOutcome outcome, OrchestrationCounters counters) {
        return switch (outcome) {
            case SUCCEEDED -> StateMachineDecision.advance(OrchestrationPhase.REVIEWING);
            // Test 不判定任务是否失败：任一测试失败（含 FAILED/FAILED_QUALITY，如自定义 TESTER）
            // 统一进入 REVIEWING，由 Review 按 BLOCKER/MAJOR 严重度闸门裁决。
            case TEST_FAILED, FAILED_QUALITY, FAILED ->
                    StateMachineDecision.advance(OrchestrationPhase.REVIEWING);
            case FAILED_INFRASTRUCTURE -> retryOrFail(phase, counters);
            case CANCELLED -> StateMachineDecision.cancelled();
        };
    }

    private StateMachineDecision reviewing(OrchestrationPhase phase, RunOutcome outcome, OrchestrationCounters counters) {
        return switch (outcome) {
            case SUCCEEDED -> StateMachineDecision.success();
            case FAILED_QUALITY -> requeueCodingOrFail(counters);
            case FAILED, TEST_FAILED -> StateMachineDecision.failed();
            case FAILED_INFRASTRUCTURE -> retryOrFail(phase, counters);
            case CANCELLED -> StateMachineDecision.cancelled();
        };
    }

    /**
     * 基础设施失败：计数内同相位重试，超限 Task FAILED。
     */
    private StateMachineDecision retryOrFail(OrchestrationPhase phase, OrchestrationCounters counters) {
        if (counters.canRetryInfra(phase)) {
            counters.incrementInfraRetries(phase);
            return StateMachineDecision.retryPhase(phase);
        }
        return StateMachineDecision.failed();
    }

    /**
     * 质量失败：计数内回到 CODING，超限 Task FAILED。
     */
    private StateMachineDecision requeueCodingOrFail(OrchestrationCounters counters) {
        if (counters.canRequeueCoding()) {
            counters.incrementQualityFixLoops();
            return StateMachineDecision.requeueCoding();
        }
        return StateMachineDecision.failed();
    }
}
