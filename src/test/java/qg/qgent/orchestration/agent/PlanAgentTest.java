package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.OrchestrationPhase;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlanAgent 单元测试：两轮按需读取产出结构化 PlanResult；非法/不完整 LLM 响应转为
 * FAILED_INFRASTRUCTURE；并且仅通过只读工具访问 Workspace。全部使用 Mock LLM，不碰真实 Key。
 */
class PlanAgentTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final PlanAgent agent = new PlanAgent(llm, codeAccess);

    private static final String PLAN_JSON = """
            {
              "taskUnderstanding": "understand",
              "implementationGoals": ["goal1"],
              "steps": [{"title":"impl","files":["src/main/java/X.java"],"description":"do it"}],
              "testPlan": "run tests",
              "risks": ["risk1"]
            }
            """;

    private AgentInput input() {
        AgentInput in = new AgentInput();
        in.setPhase(OrchestrationPhase.PLAN);
        in.setProjectId(UUID.randomUUID());
        in.setTaskId(UUID.randomUUID());
        in.setWorkspaceId(UUID.randomUUID());
        in.setTaskTitle("sample task");
        in.setRequirement("do something");
        in.setInstruction("analyze and plan");
        return in;
    }

    @Test void producesStructuredPlanFromTwoRounds() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml", "src/main/java/X.java"));
        when(codeAccess.readFile(any(), eq("src/main/java/X.java"))).thenReturn("class X {}");
        when(llm.complete(anyString(), anyString()))
                .thenReturn("{\"readRequests\":[\"src/main/java/X.java\"]}", PLAN_JSON);

        AgentRunOutcome outcome = agent.run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        PlanResult plan = outcome.getPlanResult();
        assertThat(plan.getTaskUnderstanding()).isEqualTo("understand");
        assertThat(plan.getObjectives()).containsExactly("goal1");
        assertThat(plan.getImplementationSteps()).hasSize(1);
        assertThat(plan.getImplementationSteps().get(0).getFiles()).contains("src/main/java/X.java");
        assertThat(plan.getTestPlan()).isEqualTo("run tests");
        assertThat(plan.getRisks()).containsExactly("risk1");

        verify(llm, times(2)).complete(anyString(), anyString());
        verify(codeAccess).readFile(any(), eq("src/main/java/X.java"));
    }

    @Test void proceedsWithoutFileReadsWhenSelectionEmpty() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(llm.complete(anyString(), anyString()))
                .thenReturn("{\"readRequests\":[]}", PLAN_JSON);

        AgentRunOutcome outcome = agent.run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(codeAccess, never()).readFile(any(), anyString());
    }

    @Test void illegalPlanResponseFailsInfrastructure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyString()))
                .thenReturn("{\"readRequests\":[]}", "not json at all");

        AgentRunOutcome outcome = agent.run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
    }

    @Test void incompletePlanResponseFailsInfrastructure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyString()))
                .thenReturn("{\"readRequests\":[]}", "{\"taskUnderstanding\":\"x\"}");

        AgentRunOutcome outcome = agent.run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
    }

    @Test void llmCallFailureFailsInfrastructure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyString())).thenThrow(new RuntimeException("upstream down"));

        AgentRunOutcome outcome = agent.run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
    }

    @Test void neverModifiesWorkspace() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(codeAccess.readFile(any(), eq("pom.xml"))).thenReturn("<project/>");
        when(llm.complete(anyString(), anyString()))
                .thenReturn("{\"readRequests\":[\"pom.xml\"]}", PLAN_JSON);

        agent.run(input());

        verify(codeAccess).listFiles(any());
        verify(codeAccess).readFile(any(), eq("pom.xml"));
        verify(codeAccess, never()).searchCode(any(), any());

        // 只读接口在结构上只暴露三种读方法，没有任何写方法可用。
        List<String> declaredMethods = List.of(WorkspaceCodeAccess.class.getDeclaredMethods())
                .stream().map(Method::getName).sorted().toList();
        assertThat(declaredMethods).containsExactly("listFiles", "readFile", "searchCode");
    }
}
