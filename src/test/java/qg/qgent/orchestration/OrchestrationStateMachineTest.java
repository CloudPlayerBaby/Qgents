package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 确定性状态机纯单元测试：不依赖 Spring、DB 与 Agent，直接验证相位+结果→决策的转移表。
 */
class OrchestrationStateMachineTest {
    private final OrchestrationStateMachine stateMachine = new OrchestrationStateMachine();
    private final OrchestrationCounters counters = new OrchestrationCounters();

    @Test void planSuccessAdvancesToCoding() {
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.PLAN, RunOutcome.SUCCEEDED, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.ADVANCE);
        assertThat(d.getNextPhase()).isEqualTo(OrchestrationPhase.CODING);
    }

    @Test void codingSuccessAdvancesToTesting() {
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.ADVANCE);
        assertThat(d.getNextPhase()).isEqualTo(OrchestrationPhase.TESTING);
    }

    @Test void testingSuccessAdvancesToReviewing() {
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.TESTING, RunOutcome.SUCCEEDED, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.ADVANCE);
        assertThat(d.getNextPhase()).isEqualTo(OrchestrationPhase.REVIEWING);
    }

    @Test void testEnvironmentBlockedAdvancesToReviewing() {
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.TESTING, RunOutcome.TEST_FAILED, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.ADVANCE);
        assertThat(d.getNextPhase()).isEqualTo(OrchestrationPhase.REVIEWING);
    }

    @Test void reviewingSuccessCompletesSuccess() {
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.REVIEWING, RunOutcome.SUCCEEDED, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.COMPLETE_SUCCESS);
        assertThat(d.isTerminal()).isTrue();
    }

    @Test void testQualityFailureAdvancesToReviewing() {
        // Test 不再自判失败：TESTING FAILED_QUALITY（如自定义 TESTER）也进入 REVIEWING，
        // 不触发质量循环 requeue——循环只由 REVIEWING FAILED_QUALITY 驱动。
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.TESTING, RunOutcome.FAILED_QUALITY, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.ADVANCE);
        assertThat(d.getNextPhase()).isEqualTo(OrchestrationPhase.REVIEWING);
        assertThat(counters.getQualityFixLoops()).isZero();
    }

    @Test void testFailureAdvancesToReviewing() {
        // 确定性失败（超时/无测试命令等，旧语义下直接终态失败）也统一进入 REVIEWING 由 Review 裁决。
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.TESTING, RunOutcome.FAILED, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.ADVANCE);
        assertThat(d.getNextPhase()).isEqualTo(OrchestrationPhase.REVIEWING);
    }

    @Test void reviewQualityFailureRequeuesCoding() {
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.REQUEUE_CODING);
        assertThat(d.getNextPhase()).isEqualTo(OrchestrationPhase.CODING);
        assertThat(counters.getQualityFixLoops()).isEqualTo(1);
    }

    @Test void maxQualityFixLoopsExhaustedFailsTask() {
        counters.setQualityFixLoops(counters.getMaxQualityFixLoops());
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.REVIEWING, RunOutcome.FAILED_QUALITY, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.COMPLETE_FAILED);
        assertThat(d.isTerminal()).isTrue();
    }

    @Test void infraFailureRetriesSamePhase() {
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.CODING, RunOutcome.FAILED_INFRASTRUCTURE, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.RETRY_PHASE);
        assertThat(d.getNextPhase()).isEqualTo(OrchestrationPhase.CODING);
        assertThat(counters.getInfraRetries(OrchestrationPhase.CODING)).isEqualTo(1);
    }

    @Test void infraRetriesExhaustedFailsTask() {
        counters.setInfraRetries(OrchestrationPhase.TESTING, counters.getMaxInfraRetries());
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.TESTING, RunOutcome.FAILED_INFRASTRUCTURE, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.COMPLETE_FAILED);
    }

    @Test void deterministicLlmAccountFailureDoesNotRetry() {
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.CODING,
                RunOutcome.FAILED_INFRASTRUCTURE, "LLM_ACCOUNT_ACCESS_DENIED", counters);

        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.COMPLETE_FAILED);
        assertThat(counters.getInfraRetries(OrchestrationPhase.CODING)).isZero();
    }

    @Test void infrastructureRetryBudgetsAreIndependentPerPhase() {
        counters.setInfraRetries(OrchestrationPhase.PLAN, counters.getMaxInfraRetries());

        StateMachineDecision coding = stateMachine.decide(OrchestrationPhase.CODING,
                RunOutcome.FAILED_INFRASTRUCTURE, counters);

        assertThat(coding.getAction()).isEqualTo(StateMachineDecision.Action.RETRY_PHASE);
        assertThat(counters.getInfraRetries(OrchestrationPhase.PLAN)).isEqualTo(counters.getMaxInfraRetries());
        assertThat(counters.getInfraRetries(OrchestrationPhase.CODING)).isEqualTo(1);
    }

    @Test void nonInfrastructureOutcomeResetsOnlyCurrentPhaseBudget() {
        counters.setInfraRetries(OrchestrationPhase.CODING, 2);
        counters.setInfraRetries(OrchestrationPhase.TESTING, 1);

        stateMachine.decide(OrchestrationPhase.CODING, RunOutcome.SUCCEEDED, counters);

        assertThat(counters.getInfraRetries(OrchestrationPhase.CODING)).isZero();
        assertThat(counters.getInfraRetries(OrchestrationPhase.TESTING)).isEqualTo(1);
    }

    @Test void cancelCompletesCancelled() {
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.REVIEWING, RunOutcome.CANCELLED, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.COMPLETE_CANCELLED);
        assertThat(d.isTerminal()).isTrue();
    }

    @Test void planFailureIsTerminal() {
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.PLAN, RunOutcome.FAILED, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.COMPLETE_FAILED);
        assertThat(d.isTerminal()).isTrue();
    }

    @Test void codingQualityFailureFailsTaskDirectly() {
        StateMachineDecision d = stateMachine.decide(OrchestrationPhase.CODING, RunOutcome.FAILED_QUALITY, counters);
        assertThat(d.getAction()).isEqualTo(StateMachineDecision.Action.COMPLETE_FAILED);
    }
}
