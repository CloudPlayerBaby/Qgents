package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRepairSupportTest {

    @Test
    void repairPromptContainsValidationOriginalAndFormat() {
        String prompt = JsonRepairSupport.buildPrompt(
                "说明：{\"success\":true}",
                "missing summary",
                "{\"success\":true,\"summary\":\"...\"}");

        assertThat(prompt).contains("missing summary")
                .contains("说明：{\"success\":true}")
                .contains("{\"success\":true,\"summary\":\"...\"}")
                .contains("只输出一个原始 JSON 对象");
    }

    @Test
    void repairPromptCapsOriginalOutput() {
        String prompt = JsonRepairSupport.buildPrompt("x".repeat(10_000), "invalid", "{}");

        assertThat(prompt).doesNotContain("x".repeat(8_001));
        assertThat(prompt).contains("x".repeat(8_000));
    }
}
