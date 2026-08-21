package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.TestResult;

import java.util.List;
import java.util.stream.IntStream;

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
    void rendersGreenfieldInstructionWhenWorkspaceEmpty() {
        AgentInput input = new AgentInput();
        input.setTaskTitle("图书管理系统");
        input.setRequirement("从零搭建图书管理系统");

        String prompt = promptBuilder.buildUser(input, List.of());

        assertThat(prompt).contains("绿地任务：工作区为空")
                .contains("用 create_directory 建立目录结构")
                .contains("只输出方案而不建任何文件属于失败");
    }

    @Test
    void omitsGreenfieldInstructionWhenWorkspaceHasFiles() {
        AgentInput input = new AgentInput();
        input.setTaskTitle("图书管理系统");
        input.setRequirement("从零搭建图书管理系统");

        String prompt = promptBuilder.buildUser(input, List.of("pom.xml", "src/main/java/Book.java"));

        assertThat(prompt).doesNotContain("绿地任务：工作区为空");
    }

    @Test
    void correctiveGiveUpInstructionDemandsActualToolWrites() {
        String instruction = CodingPromptBuilder.correctiveGiveUpInstruction();

        assertThat(instruction).contains("纠正指令")
                .contains("未动手就放弃")
                .contains("只有产生真实写入");
        // 纠正文案不应出现于默认系统提示，避免首次执行就被引导为"已放弃"。
        assertThat(promptBuilder.buildSystem(true)).doesNotContain("纠正指令");
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
    void documentsMultiRepositoryPathContract() {
        assertThat(promptBuilder.buildSystem())
                .contains("workspacePath 开头")
                .contains("新建目录和新建文件")
                .contains("禁止使用无法确定仓库的裸路径");
    }

    private static TestResult.Failure failure(String name, String reason) {
        TestResult.Failure failure = new TestResult.Failure();
        failure.setName(name);
        failure.setReason(reason);
        return failure;
    }

    @Test
    void skipsTestFailureListWhenFeedbackPresentToAvoidDuplication() {
        AgentInput input = new AgentInput();
        input.setTaskTitle("修复导出逻辑");
        input.setRequirement("修复导出接口");
        input.setFeedback("前一轮测试失败：[testExport: expected 5 but got 4]");
        TestResult test = new TestResult();
        test.setSuccess(false);
        test.setExitCode(1);
        test.setSummary("导出接口测试失败");
        test.setFailures(List.of(failure("testExport", "expected 5 but got 4")));
        input.setTestResult(test);

        String prompt = promptBuilder.buildUser(input, List.of("src/main/java/ExportService.java"));

        // 打回重做时失败明细已由 feedback 承载：整体状态与摘要保留，失败项不再重复渲染。
        assertThat(prompt).contains("上次测试结果：未通过").contains("测试摘要：导出接口测试失败")
                .doesNotContain("测试失败项：");
    }

    @Test
    void rendersTestFailureListWhenNoFeedback() {
        AgentInput input = new AgentInput();
        input.setTaskTitle("修复导出逻辑");
        input.setRequirement("修复导出接口");
        TestResult test = new TestResult();
        test.setSuccess(false);
        test.setExitCode(1);
        test.setFailures(List.of(failure("testExport", "expected 5 but got 4")));
        input.setTestResult(test);

        String prompt = promptBuilder.buildUser(input, List.of("src/main/java/ExportService.java"));

        assertThat(prompt).contains("上次测试结果：未通过").contains("测试失败项：").contains("testExport");
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

    @Test
    void boundsWorkspaceTreeWhileKeepingItsHeadAndTail() {
        AgentInput input = new AgentInput();
        input.setTaskTitle("large workspace");
        List<String> files = IntStream.range(0, 3_000)
                .mapToObj(i -> "src/main/java/example/File" + i + ".java")
                .toList();

        String prompt = promptBuilder.buildUser(input, files);

        assertThat(prompt).hasSizeLessThan(CodingPromptBuilder.MAX_FILE_TREE_CHARS + 2_000)
                .contains(files.get(0), files.get(files.size() - 1), PromptTextLimiter.TRUNCATION_MARKER);
    }

    @Test
    void nativeSystemStatesToolSelectionAndFailureRecoveryRules() {
        String system = promptBuilder.buildSystem(true);

        assertThat(system).contains("原生函数调用", "不要把工具调用 JSON 写进普通文本")
                .contains("errorCode、retryable、nextAction")
                .contains("已有文件严格使用 apply_patch", "父目录由工具自动准备")
                .contains("基础设施错误时不得伪造成功");
    }

    @Test
    void nativeSystemProactivelyUsesRelevantSkills() {
        assertThat(promptBuilder.buildSystem(true))
                .contains("Skill 决策是编码前置步骤")
                .contains("在第一次 read_file、write_file、apply_patch 或 replace_file 之前")
                .contains("必须优先对其中最相关的 Skill 调用 activate_skill 获取全文")
                .contains("不要等待 Reviewer 指出遗漏后才读取")
                .contains("只有逐项确认目录内全部 Skill 与本次任务无关时，才可不调用");
    }

    @Test
    void documentsPatchFormatRecoveryWithoutRelaxingPatchValidation() {
        assertThat(promptBuilder.buildSystem(true))
                .contains("FILE_PATCH_FAILED", "重新生成完整 unified diff", "新文件时改用 write_file");
    }

    @Test
    void nativeSystemDocumentsPatchEscalationAndRetryReality() {
        String system = promptBuilder.buildSystem(true);

        // apply_patch 连续失败升级规则、replace_file 修复路径、打回重做必须产生真实变更。
        assertThat(system)
                .contains("连续失败 3 次后必须改用 replace_file")
                .contains("replace_file 提供完整文件内容")
                .contains("收到前一轮反馈或重试上下文（打回重做）时")
                .contains("只读复核、重复已存在内容、确认现状或空操作不构成完成");
    }

    @Test
    void appendsCustomAgentOverlayToNativeAndLegacySystem() {
        String nativeBase = promptBuilder.buildSystem(true);
        String nativeOverlaid = promptBuilder.buildSystem(true, "优先关注权限与异常路径");

        assertThat(nativeOverlaid).startsWith(nativeBase)
                .contains("[自定义 Agent 的补充指引]").contains("优先关注权限与异常路径")
                .contains("不得覆盖上文系统提示中的真实结果约束与判定规则");

        String legacyBase = promptBuilder.buildSystem(false);
        assertThat(promptBuilder.buildSystem(false, "优先关注权限与异常路径"))
                .startsWith(legacyBase).contains("[自定义 Agent 的补充指引]");
    }

    @Test
    void blankOverlayLeavesCodingSystemUnchanged() {
        assertThat(promptBuilder.buildSystem(true, null)).isEqualTo(promptBuilder.buildSystem(true));
        assertThat(promptBuilder.buildSystem(true, "  ")).isEqualTo(promptBuilder.buildSystem(true));
        assertThat(promptBuilder.buildSystem(false, null)).isEqualTo(promptBuilder.buildSystem(false));
    }
}
