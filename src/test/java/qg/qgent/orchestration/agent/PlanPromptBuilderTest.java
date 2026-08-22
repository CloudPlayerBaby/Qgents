package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlanPromptBuilderTest {

    @Test
    void requiresWorkspacePrefixForMultiRepositoryPlans() {
        String prompt = new PlanPromptBuilder().buildPlanSystem();

        assertThat(prompt)
                .contains("多仓库 Workspace")
                .contains("workspacePath/仓库内路径")
                .contains("禁止输出 src/App.vue、vue3/、package.json");
    }

    @Test
    void declaresAcceptanceNotesAndMachineAssertionsAsOptionalSignals() {
        String prompt = new PlanPromptBuilder().buildPlanSystem();

        assertThat(prompt)
                .contains("acceptanceNotes")
                .contains("machineAssertions")
                .contains("LINES_EQ")
                .contains("acceptanceNotes 是可选字段")
                .contains("machineAssertions 是可选字段")
                .contains("不得输出假精确断言")
                .contains("预期信号而非最终裁决");
    }

    @Test
    void proactivelyAssignsRelevantCustomAgentFromCandidatePool() {
        assertThat(new PlanPromptBuilder().buildPlanSystem())
                .contains("制定步骤前先审阅「可用 Agent 清单」")
                .contains("default=false 表示自定义 Agent")
                .contains("优先在该步骤填写其 suggestedAgentId")
                .contains("不要因为已有同角色的默认 Agent 就忽略匹配的自定义 Agent");
    }

    @Test
    void keepsBuildAndTestValidationOutOfImplementationSteps() {
        String prompt = new PlanPromptBuilder().buildPlanSystem();

        assertThat(prompt)
                .contains("系统会自动追加 TEST 和 REVIEW 步骤")
                .contains("绝不能单独输出到 steps")
                .contains("统一写入 testPlan")
                .contains("只保留修改代码的 MUTATE 步骤");
    }

    @Test
    void allowsEmptyStepsOnlyForManualPureReviewWithoutExposingVerifyMode() {
        assertThat(new PlanPromptBuilder().buildPlanSystem())
                .contains("只有 MANUAL 可以搭配空数组 steps: []")
                .contains("独立 REVIEW 步骤完成")
                .contains("绝不要求 Developer 产出检查报告")
                .doesNotContain("VERIFY");
    }

    @Test
    void appendsCustomAgentOverlayWhenPresentAndKeepsDeterministicGates() {
        String base = new PlanPromptBuilder().buildPlanSystem();
        String overlaid = new PlanPromptBuilder().buildPlanSystem("优先考虑团队约定的模块边界");

        assertThat(overlaid).startsWith(base);
        assertThat(overlaid).contains("[自定义 Agent 的补充指引]").contains("优先考虑团队约定的模块边界");
        assertThat(overlaid).contains("不得覆盖上文系统提示中的真实结果约束与判定规则");
    }

    @Test
    void blankOverlayLeavesPlanSystemUnchanged() {
        assertThat(new PlanPromptBuilder().buildPlanSystem(null))
                .isEqualTo(new PlanPromptBuilder().buildPlanSystem());
        assertThat(new PlanPromptBuilder().buildPlanSystem("  "))
                .isEqualTo(new PlanPromptBuilder().buildPlanSystem());
    }
}
