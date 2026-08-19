package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.entity.AgentEntity;
import qg.qgent.orchestration.AgentDispatcher;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.OrchestrationPhase;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmOutputTruncatedException;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceFileReadResult;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlanAgent 单元测试：两轮按需读取产出结构化 PlanResult；联合规划会把团队候选 Agent 池注入
 * 计划提示词（池查询失败降级为空池）；非法/不完整 LLM 响应转为 FAILED_INFRASTRUCTURE；
 * 并且仅通过只读工具访问 Workspace。全部使用 Mock LLM，不碰真实 Key。
 */
class PlanAgentTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final AgentDispatcher dispatcher = mock(AgentDispatcher.class);
    private final AttachmentMediaLoader attachmentMediaLoader = mock(AttachmentMediaLoader.class);
    private final PlanAgent agent = new PlanAgent(llm, codeAccess, dispatcher, attachmentMediaLoader);

    @BeforeEach
    void stubEmptyAttachments() {
        when(attachmentMediaLoader.load(any(), any(), any()))
                .thenReturn(new AttachmentMediaLoader.Result(List.of(), ""));
    }

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
        when(codeAccess.readFile(any(), eq("src/main/java/X.java")))
                .thenReturn(WorkspaceFileReadResult.ok("src/main/java/X.java", "class X {}", "hash"));
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

    @Test void malformedPlanResponseIsRepairedOnce() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyString()))
                .thenReturn("{\"readRequests\":[]}", "计划结果：不是严格 JSON");
        when(llm.complete(anyString(), anyList())).thenReturn(PLAN_JSON);

        AgentRunOutcome outcome = agent.run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getPlanResult().getTaskUnderstanding()).isEqualTo("understand");
        verify(llm, times(2)).complete(anyString(), anyString());
        verify(llm).complete(anyString(), anyList());
    }

    @Test void llmCallFailureFailsInfrastructure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyString())).thenThrow(new RuntimeException("upstream down"));

        AgentRunOutcome outcome = agent.run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
    }

    @Test void truncatedPlanOutputKeepsStableFailureCode() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyString()))
                .thenReturn("{\"readRequests\":[]}")
                .thenThrow(new LlmOutputTruncatedException(128));

        AgentRunOutcome outcome = agent.run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.LLM_FINISH_LENGTH.name());
    }

    @Test void neverModifiesWorkspace() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(codeAccess.readFile(any(), eq("pom.xml")))
                .thenReturn(WorkspaceFileReadResult.ok("pom.xml", "<project/>", "hash"));
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

    @Test void injectsAvailableAgentPoolIntoPlanPrompt() {
        AgentEntity dev = new AgentEntity();
        dev.setId(UUID.randomUUID());
        dev.setName("Java 后端");
        dev.setRole("DEVELOPER");
        dev.setDescription("负责后端实现");
        when(dispatcher.listTeamCandidates(any(), any())).thenReturn(List.of(dev));
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyString()))
                .thenReturn("{\"readRequests\":[]}", PLAN_JSON);

        agent.run(input());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(anyString(), captor.capture());
        String planUser = captor.getAllValues().get(1);
        assertThat(planUser).contains(dev.getId().toString())
                .contains("Java 后端")
                .contains("DEVELOPER")
                .contains("负责后端实现");
    }

    @Test void poolQueryFailureDegradesToPlanningWithoutPool() {
        when(dispatcher.listTeamCandidates(any(), any())).thenThrow(new IllegalStateException("db down"));
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyString()))
                .thenReturn("{\"readRequests\":[]}", PLAN_JSON);

        AgentRunOutcome outcome = agent.run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getPlanResult().getImplementationSteps()).hasSize(1);
    }

    @Test void planRetryFeedbackIsVisibleToPlanner() {
        AgentInput input = input();
        input.setFeedback("前一轮基础设施失败（LLM_FINISH_LENGTH）：模型结构化输出因长度上限未完成");
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyString())).thenReturn("{\"readRequests\":[]}", PLAN_JSON);

        agent.run(input);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(anyString(), captor.capture());
        assertThat(captor.getAllValues().get(1)).contains("上次规划失败反馈", "LLM_FINISH_LENGTH");
    }

    @Test void boundsFileTreeAndPlannerFileContentsInModelPrompt() {
        List<String> files = IntStream.range(0, 2_000)
                .mapToObj(i -> "src/main/java/example/VeryLongFileName" + i + ".java")
                .toList();
        when(codeAccess.listFiles(any())).thenReturn(files);
        when(codeAccess.readFile(any(), anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(1);
            return WorkspaceFileReadResult.ok(path,
                    "HEAD-" + path + "\n" + "x".repeat(80_000) + "\n-TAIL-" + path, "hash");
        });
        when(llm.complete(anyString(), anyString()))
                .thenReturn("{\"readRequests\":[\"" + files.get(0) + "\",\"" + files.get(1) + "\",\""
                                + files.get(2) + "\",\"" + files.get(3) + "\"]}", PLAN_JSON);

        assertThat(agent.run(input()).getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(anyString(), captor.capture());
        String selectionPrompt = captor.getAllValues().get(0);
        String planPrompt = captor.getAllValues().get(1);
        assertThat(selectionPrompt).hasSizeLessThan(PlanPromptBuilder.MAX_FILE_TREE_CHARS + 2_000)
                .contains(files.get(0), files.get(files.size() - 1), PromptTextLimiter.TRUNCATION_MARKER);
        assertThat(planPrompt).hasSizeLessThan(PlanPromptBuilder.MAX_FILE_TREE_CHARS
                        + PlanPromptBuilder.MAX_TOTAL_FILE_CONTENT_CHARS + 10_000)
                .contains("HEAD-", "-TAIL-", PromptTextLimiter.TRUNCATION_MARKER);
    }
}
