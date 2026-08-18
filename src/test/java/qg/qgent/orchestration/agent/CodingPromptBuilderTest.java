package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.result.CodingResult;

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

    @Test
    void rendersPreviousCodingResultForSequentialDeveloperSteps() {
        AgentInput input = new AgentInput();
        input.setTaskTitle("汇总检查报告");
        CodingResult previous = new CodingResult();
        previous.setSuccess(true);
        previous.setSummary("已完成 repo-2 检查");
        previous.setModifiedFiles(List.of("repo-2/CHECK_REPORT.md"));
        previous.setChanges(List.of("写入基础检查结果"));
        input.setCodingResult(previous);

        String prompt = promptBuilder.buildUser(input, List.of("repo-2/CHECK_REPORT.md"));

        assertThat(prompt).contains("前序 Developer 产物")
                .contains("repo-2/CHECK_REPORT.md")
                .contains("已完成 repo-2 检查")
                .contains("不代表测试失败反馈");
    }
}
