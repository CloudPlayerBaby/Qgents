package qg.qgent.orchestration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Agent Run 单相位总时限配置（prefix= qgents.orchestration）。
 * <p>
 * 每次 `agent.run()` 都必须在对应相位时限内返回；超时统一按基础设施失败（FAILED_INFRASTRUCTURE）
 * 落库，失败码 AGENT_RUN_TIMEOUT。时限应小于恢复器的陈旧 run 阈值
 * （qgents.task-recovery.stale-run-threshold），避免真实执行中的 run 被误判为陈旧而回收。
 */
@ConfigurationProperties("qgents.orchestration")
public class OrchestrationTimeoutProperties {

    /**
     * CODING（开发）相位总时限。
     */
    private Duration codingTimeout = Duration.ofMinutes(10);

    /**
     * TESTING（测试）相位总时限，应为此中最大，且必小于恢复器陈旧 run 阈值。
     */
    private Duration testingTimeout = Duration.ofMinutes(15);

    /**
     * REVIEWING（审查）相位总时限。
     */
    private Duration reviewingTimeout = Duration.ofMinutes(8);

    public Duration codingTimeout() {
        return codingTimeout;
    }

    public void setCodingTimeout(Duration codingTimeout) {
        this.codingTimeout = codingTimeout;
    }

    public Duration testingTimeout() {
        return testingTimeout;
    }

    public void setTestingTimeout(Duration testingTimeout) {
        this.testingTimeout = testingTimeout;
    }

    public Duration reviewingTimeout() {
        return reviewingTimeout;
    }

    public void setReviewingTimeout(Duration reviewingTimeout) {
        this.reviewingTimeout = reviewingTimeout;
    }

    /**
     * 按相位取总时限；PLAN 无独立配置，回退到 CODING 时限。
     */
    public Duration timeoutFor(OrchestrationPhase phase) {
        return switch (phase == null ? OrchestrationPhase.CODING : phase) {
            case TESTING -> testingTimeout;
            case REVIEWING -> reviewingTimeout;
            default -> codingTimeout;
        };
    }
}
