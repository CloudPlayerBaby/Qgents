package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.AgentInput;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coding prompt 渲染测试：质量修复重试的失败上下文必须进入模型用户消息。
 */
class CodingPromptBuilderTest {

    private final CodingPromptBuilder promptBuilder = new CodingPromptBuilder();

    @Test
    void rendersPreviousFailureFeedback() {
        AgentInput input = new AgentInput();
        input.setTaskTitle("修复导出逻辑");
        input.setRequirement("修复导出接口");
        input.setFeedback("前一轮测试失败：expected 5 but got 4");

        String prompt = promptBuilder.buildUser(input, List.of("src/main/java/ExportService.java"));

        assertThat(prompt).contains("前一轮反馈：")
                .contains("前一轮测试失败：expected 5 but got 4");
    }

    @Test
    void omitsPreviousFailureFeedbackWhenEmpty() {
        AgentInput input = new AgentInput();
        input.setTaskTitle("修复导出逻辑");
        input.setRequirement("修复导出接口");

        String prompt = promptBuilder.buildUser(input, List.of());

        assertThat(prompt).doesNotContain("前一轮反馈：");
    }
}
