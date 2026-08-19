package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.dto.ContextMessage;
import qg.qgent.orchestration.AgentInput;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContextLogFormatterTest {

    @Test
    void summaryShowsContextShapeWithoutRawContent() {
        AgentInput input = new AgentInput();
        input.setRequirement("token=do-not-log");
        input.setConversation(List.of(new ContextMessage(7L, "TEXT", "USER", null, "hello")));

        String summary = AgentContextLogFormatter.summary(input);

        assertThat(summary).contains("requirementChars=16", "conversationCount=1", "conversationChars=5")
                .doesNotContain("do-not-log", "hello");
    }

    @Test
    void debugSamplesAreBoundedAndSanitized() {
        AgentInput input = new AgentInput();
        input.setRequirement("Bearer very-secret-value password=also-secret " + "x".repeat(100));

        String samples = AgentContextLogFormatter.samples(input, 80);

        assertThat(samples).contains("Bearer [redacted]", "password=[redacted]")
                .doesNotContain("very-secret-value", "also-secret")
                .contains("...");
    }
}
