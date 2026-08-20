package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.orchestration.tool.GitDiffResult;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPromptBuilderTest {

    private final ReviewPromptBuilder promptBuilder = new ReviewPromptBuilder();

    @Test
    void boundsWorkspaceTreeWhileKeepingItsHeadAndTail() {
        AgentInput input = new AgentInput();
        input.setTaskTitle("large workspace review");
        List<String> files = IntStream.range(0, 3_000)
                .mapToObj(i -> "src/main/java/example/File" + i + ".java")
                .toList();

        String prompt = promptBuilder.buildUser(input, files, GitDiffResult.ok("diff", "base", "head"));

        assertThat(prompt).hasSizeLessThan(ReviewPromptBuilder.MAX_FILE_TREE_CHARS + 3_000)
                .contains(files.get(0), files.get(files.size() - 1), PromptTextLimiter.TRUNCATION_MARKER);
    }

    @Test
    void retryPromptPrioritizesPreviousReviewFindings() {
        AgentInput input = new AgentInput();
        input.setFeedback("前一轮审查问题：MAJOR missing ownership check");

        String system = promptBuilder.buildSystem();
        String user = promptBuilder.buildUser(input, List.of(), GitDiffResult.ok("diff", "base", "head"));

        assertThat(system).contains("旧 finding", "优先复核");
        assertThat(user).contains("待复核的上一轮审查反馈", "missing ownership check");
    }

    @Test
    void systemPromptKeepsReviewToolsReadOnlyAndExplainsToolErrors() {
        String system = promptBuilder.buildSystem(true);

        assertThat(system).contains("全部只读", "errorCode、retryable、nextAction")
                .contains("没有任何写权限", "不要尝试调用 apply_patch");
    }

    @Test
    void boundsLargeDiffCopyAndKeepsTrustedModifiedFileScope() {
        String rawDiff = "DIFF-HEAD\n" + "x".repeat(100_000) + "\nDIFF-TAIL";
        GitDiffResult diff = GitDiffResult.ok(rawDiff, "base", "head");
        AgentInput input = new AgentInput();
        CodingResult coding = new CodingResult();
        coding.setModifiedFiles(List.of("src/main/java/Trusted.java", "src/test/java/TrustedTest.java"));
        input.setCodingResult(coding);

        String prompt = promptBuilder.buildUser(input, List.of(), diff);

        assertThat(prompt).hasSizeLessThan(ReviewPromptBuilder.MAX_DIFF_CHARS + 3_000)
                .contains("DIFF-HEAD", "DIFF-TAIL", ReviewPromptBuilder.DIFF_TRUNCATION_MARKER,
                        "服务端可信修改文件范围", "src/main/java/Trusted.java", "read_file");
        assertThat(diff.diff()).isSameAs(rawDiff);
        assertThat(promptBuilder.buildSystem()).contains("Git Diff 标记已裁剪", "read_file");
    }

    @Test
    void systemPromptAnchorsReviewToUserRequirementNotPlanWording() {
        String nativeSystem = promptBuilder.buildSystem(true);
        String legacySystem = promptBuilder.buildSystem(false);

        assertThat(nativeSystem)
                .contains("用户需求（任务描述）")
                .contains("不应逐字对照计划措辞判错")
                .contains("合理超额实现")
                .contains("不得判 MAJOR/BLOCKER")
                .contains("核实偏差理由")
                .contains("断言是否真正反映用户需求");
        // legacy 协议仅同步判定文本，不新增字段解析。
        assertThat(legacySystem).contains("用户需求（任务描述）");
    }

    @Test
    void userPromptRendersDeviationsAndAssertionSignals() {
        AgentInput input = new AgentInput();
        input.setTaskTitle("append config");

        CodingResult coding = new CodingResult();
        coding.setSummary("已追加两行配置");
        coding.setModifiedFiles(List.of("conf/app.yml"));
        coding.setDeviations(List.of("计划要求追加 1 行，实际追加 2 行：用户指令'再加一点'语义更符合"));
        input.setCodingResult(coding);

        PlanResult plan = new PlanResult();
        PlanResult.ImplementationStep step = new PlanResult.ImplementationStep();
        step.setTitle("追加配置");
        PlanResult.Assertion assertion = new PlanResult.Assertion();
        assertion.setType("LINES_EQ");
        assertion.setFile("conf/app.yml");
        assertion.setValue("4");
        step.setMachineAssertions(List.of(assertion));
        plan.setImplementationSteps(List.of(step));
        input.setPlanResult(plan);

        TestResult test = new TestResult();
        TestResult.FileAssertion fileAssertion = new TestResult.FileAssertion();
        fileAssertion.setFile("conf/app.yml");
        fileAssertion.setType("LINES_EQ");
        fileAssertion.setExpected("4");
        fileAssertion.setActual("5");
        fileAssertion.setPassed(false);
        test.setAssertionResults(List.of(fileAssertion));
        input.setTestResult(test);

        String user = promptBuilder.buildUser(input, List.of(), GitDiffResult.ok("diff", "base", "head"));

        assertThat(user)
                .contains("Coding 自声明偏差", "计划要求追加 1 行，实际追加 2 行")
                .contains("计划预期断言（machineAssertions", "LINES_EQ")
                .contains("Test 断言校验结果（assertionResults", "实际=5", "未满足");
    }
}
