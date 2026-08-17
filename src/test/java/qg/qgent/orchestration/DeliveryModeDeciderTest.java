package qg.qgent.orchestration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交付模式硬规则兜底判定测试：单仓/多仓、开发步骤数、受保护分支门禁三类信号。
 */
class DeliveryModeDeciderTest {

    private final DeliveryModeDecider decider = new DeliveryModeDecider();

    @Test
    void singleRepositoryFewStepsNoGateDefaultsToDiffFirst() {
        assertThat(decider.decide(1, 1, false)).isEqualTo(DeliveryMode.DIFF_FIRST);
        assertThat(decider.decide(1, 2, false)).isEqualTo(DeliveryMode.DIFF_FIRST);
    }

    @Test
    void multipleRepositoriesAlwaysMrFirst() {
        assertThat(decider.decide(2, 1, false)).isEqualTo(DeliveryMode.MR_FIRST);
        assertThat(decider.decide(3, 1, false)).isEqualTo(DeliveryMode.MR_FIRST);
    }

    @Test
    void developerStepsOverTwoAlwaysMrFirst() {
        assertThat(decider.decide(1, 3, false)).isEqualTo(DeliveryMode.MR_FIRST);
        assertThat(decider.decide(1, 4, false)).isEqualTo(DeliveryMode.MR_FIRST);
    }

    @Test
    void protectedBranchWithRequiredChecksMrFirst() {
        assertThat(decider.decide(1, 1, true)).isEqualTo(DeliveryMode.MR_FIRST);
        assertThat(decider.decide(1, 2, true)).isEqualTo(DeliveryMode.MR_FIRST);
    }

    @Test
    void rulePriorityRepositoryOverStepsAndGate() {
        assertThat(decider.decide(2, 1, false)).isEqualTo(DeliveryMode.MR_FIRST);
    }
}