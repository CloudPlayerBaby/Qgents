package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptTextLimiterTest {

    @Test
    void keepsHeadAndTailWhenTextExceedsLimit() {
        String value = "HEAD-" + "x".repeat(200) + "-TAIL";

        String limited = PromptTextLimiter.limitHeadTail(value, 80);

        assertThat(limited).hasSizeLessThanOrEqualTo(80)
                .startsWith("HEAD-")
                .endsWith("-TAIL")
                .contains(PromptTextLimiter.TRUNCATION_MARKER);
    }

    @Test
    void leavesShortTextUnchanged() {
        assertThat(PromptTextLimiter.limitHeadTail("short", 80)).isEqualTo("short");
    }
}
