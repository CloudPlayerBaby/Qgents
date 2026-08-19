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
}
