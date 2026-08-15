package qg.qgent.orchestration;

import lombok.Getter;

/**
 * 状态机对一次 Agent 结果的确定性决策。Action 决定下一步动作，
 * nextPhase 仅在 ADVANCE/REQUEUE_CODING/RETRY_PHASE 时有意义。
 */
@Getter
public class StateMachineDecision {
    public enum Action {
        /**
         * 进入 nextPhase（如 PLAN→CODING、CODING→TESTING）。
         */
        ADVANCE,
        /**
         * 同相位基础设施重试。
         */
        RETRY_PHASE,
        /**
         * Test/Review 质量失败，回到 CODING 修复。
         */
        REQUEUE_CODING,
        /**
         * Task 成功完成。
         */
        COMPLETE_SUCCESS,
        /**
         * Task 失败（质量循环超限或不可修复）。
         */
        COMPLETE_FAILED,
        /**
         * Task 被取消。
         */
        COMPLETE_CANCELLED
    }

    private final Action action;
    private final OrchestrationPhase nextPhase;

    private StateMachineDecision(Action action, OrchestrationPhase nextPhase) {
        this.action = action;
        this.nextPhase = nextPhase;
    }

    public static StateMachineDecision advance(OrchestrationPhase next) {
        return new StateMachineDecision(Action.ADVANCE, next);
    }

    public static StateMachineDecision retryPhase(OrchestrationPhase same) {
        return new StateMachineDecision(Action.RETRY_PHASE, same);
    }

    public static StateMachineDecision requeueCoding() {
        return new StateMachineDecision(Action.REQUEUE_CODING, OrchestrationPhase.CODING);
    }

    public static StateMachineDecision success() {
        return new StateMachineDecision(Action.COMPLETE_SUCCESS, null);
    }

    public static StateMachineDecision failed() {
        return new StateMachineDecision(Action.COMPLETE_FAILED, null);
    }

    public static StateMachineDecision cancelled() {
        return new StateMachineDecision(Action.COMPLETE_CANCELLED, null);
    }

    /**
     * 是否进入 Task 级终态。
     */
    public boolean isTerminal() {
        return action == Action.COMPLETE_SUCCESS || action == Action.COMPLETE_FAILED
                || action == Action.COMPLETE_CANCELLED;
    }
}
