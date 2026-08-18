package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.OrchestrationPhase;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.ToolTurnResult;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.orchestration.tool.DisabledWorkspaceDiffAccess;
import qg.qgent.orchestration.tool.GitDiffResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceDiffAccess;
import qg.qgent.service.ContextService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewAgent 单元测试：默认协议（native）以 mock {@link LlmClient#nextToolTurn} 驱动原生
 * 只读工具循环，覆盖 diff 预取嵌入、severity 判定策略、错误码分类、基础设施失败与观测落库；
 * legacy 手写 JSON 协议（灰度期）以 mock {@link LlmClient#complete} 做少量回归。Review 只读
 * 的结构性保证（无 write 工具）由 {@link ReviewTools} 只读端口测试覆盖。不启动 Spring、不写 API Key。
 */
class ReviewAgentTest {

    private static final int MAX_TOOL_ROUNDS = 20;

    private final LlmClient llm = mock(LlmClient.class);
    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final WorkspaceDiffAccess diffAccess = mock(WorkspaceDiffAccess.class);
    private final UUID workspaceId = UUID.randomUUID();

    private ReviewAgent nativeAgent() {
        return new ReviewAgent(llm, codeAccess, diffAccess, AgentProtocol.nativeDefault(),
                mock(ContextService.class), new ContextSearchProperties(10));
    }

    private ReviewAgent legacyAgent() {
        return new ReviewAgent(llm, codeAccess, diffAccess, new AgentProtocol("legacy"),
                mock(ContextService.class), new ContextSearchProperties(10));
    }

    // ---------- 原生 Tool Calling（默认协议） ----------

    @Test
    void nativePassingReviewEmbedsDiffAndSucceeds() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff --git a/X.java b/X.java", "base-sha", "head-sha"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn(reviewJson(true, "review passed", "[]")));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getReviewResult().isSuccess()).isTrue();
        assertThat(outcome.getObservations()).hasSize(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm).nextToolTurn(anyString(), historyCaptor.capture(), anyList());
        String userMessage = historyCaptor.getValue().stream()
                .filter(m -> m instanceof UserMessage).findFirst().orElseThrow().getText();
        assertThat(userMessage)
                .contains("diff --git a/X.java b/X.java")
                .contains("base-sha")
                .contains("head-sha")
                .contains("implemented calculator")
                .contains("tests passed");
    }

    @Test
    void nativeMajorFindingForcesQualityFail() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        // LLM 声称通过，但存在 MAJOR finding，必须强制 FAIL。
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":" + reviewJson(true, "looks fine",
                        "[{\"file\":\"src/main/java/X.java\",\"severity\":\"MAJOR\","
                                + "\"issue\":\"null check missing\",\"suggestion\":\"add null check\"}]") + "}"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getReviewResult().isSuccess()).isFalse();
        assertThat(outcome.getReviewResult().getFindings().get(0).getSeverity()).isEqualTo("MAJOR");
    }

    @Test
    void nativeBlockerWithoutCodingFixIsTerminalFail() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        // needsCodingFix=false：BLOCKER 不可由 Coding Agent 修复 → 终态 FAIL 而非质量回环。
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":{\"success\":true,\"summary\":\"blocked\","
                        + "\"findings\":[{\"file\":\"src/main/java/X.java\",\"severity\":\"BLOCKER\","
                        + "\"issue\":\"cannot be auto-fixed\",\"suggestion\":\"replan\"}],"
                        + "\"suggestions\":[],\"needsCodingFix\":false}}"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getReviewResult().isSuccess()).isFalse();
    }

    @Test
    void nativeMinorOnlyRespectsLlmSuccess() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":" + reviewJson(true, "ok with minor note",
                        "[{\"file\":\"src/main/java/X.java\",\"severity\":\"MINOR\","
                                + "\"issue\":\"method name unclear\",\"suggestion\":\"rename\"}]") + "}"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getReviewResult().isSuccess()).isTrue();
    }

    @Test
    void nativeIllegalFinalTextMapsToToolCallMalformed() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"unexpected\":true}"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED.name());
    }

    @Test
    void nativeInfraAbortMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(ToolTurnResult.infraAbort("workspace unavailable", "stop", 20, 10, "aabb", "read_file"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("workspace unavailable");
        verify(llm, times(1)).nextToolTurn(anyString(), anyList(), anyList());
    }

    @Test
    void nativeFinishLengthMapsToLlmFinishLength() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurnWithReason("{\"finalResult\":{\"success\":true,\"summary\":\"tr", "LENGTH"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains(ProtocolFailureCode.LLM_FINISH_LENGTH.name());
    }

    @Test
    void nativeExceedingMaxRoundsFailsContextLimit() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(toolTurn("read_file"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains(ProtocolFailureCode.LLM_CONTEXT_LIMIT.name());
        verify(llm, times(MAX_TOOL_ROUNDS)).nextToolTurn(anyString(), anyList(), anyList());
        assertThat(outcome.getObservations()).hasSize(MAX_TOOL_ROUNDS);
    }

    @Test
    void nativeGitDiffUnavailableMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.unavailable());

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        verify(llm, never()).nextToolTurn(anyString(), anyList(), anyList());
    }

    @Test
    void nativeLlmCallFailureMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("llm down"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
    }

    @Test
    void realDisabledDiffAccessMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        ReviewAgent disabledAgent = new ReviewAgent(llm, codeAccess, new DisabledWorkspaceDiffAccess(),
                AgentProtocol.nativeDefault(), mock(ContextService.class), new ContextSearchProperties(10));

        AgentRunOutcome outcome = disabledAgent.run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        verify(llm, never()).nextToolTurn(anyString(), anyList(), anyList());
    }

    // ---------- legacy 手写 JSON 协议（灰度期回归） ----------

    @Test
    void legacyPassingReviewSucceeds() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"review passed\",\"findings\":[],\"suggestions\":[],\"needsCodingFix\":false}}");

        AgentRunOutcome outcome = legacyAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getReviewResult().isSuccess()).isTrue();
    }

    @Test
    void legacyIllegalLlmResponseMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.complete(anyString(), anyList())).thenReturn("{\"unexpected\":true}");

        AgentRunOutcome outcome = legacyAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED.name());
    }

    // ---------- 辅助 ----------

    private AgentInput input() {
        AgentInput input = new AgentInput();
        input.setProjectId(UUID.randomUUID());
        input.setTaskId(UUID.randomUUID());
        input.setTaskTitle("sample task");
        input.setRequirement("implement a calculator");
        input.setInstruction("review the change");
        input.setPhase(OrchestrationPhase.REVIEWING);
        input.setWorkspaceId(workspaceId);
        PlanResult plan = new PlanResult();
        plan.setTaskUnderstanding("add a calculator");
        plan.setObjectives(List.of("add add()"));
        PlanResult.ImplementationStep step = new PlanResult.ImplementationStep();
        step.setTitle("impl");
        step.setFiles(List.of("src/main/java/Calculator.java"));
        plan.setImplementationSteps(List.of(step));
        plan.setTestPlan("run tests");
        input.setPlanResult(plan);
        CodingResult coding = new CodingResult();
        coding.setSuccess(true);
        coding.setSummary("implemented calculator");
        coding.setModifiedFiles(List.of("src/main/java/Calculator.java"));
        input.setCodingResult(coding);
        TestResult test = new TestResult();
        test.setSuccess(true);
        test.setExitCode(0);
        test.setSummary("tests passed");
        input.setTestResult(test);
        return input;
    }

    private ToolTurnResult finalTurn(String json) {
        return finalTurnWithReason(json, "stop");
    }

    private ToolTurnResult finalTurnWithReason(String json, String finishReason) {
        return ToolTurnResult.finalAnswer(json, finishReason, 20, 10, "aabb", null);
    }

    private ToolTurnResult toolTurn(String toolName) {
        return ToolTurnResult.continueTools(List.of(new UserMessage("tool executed: " + toolName)),
                "stop", 20, 10, "aabb", toolName, null);
    }

    private String reviewJson(boolean success, String summary, String findings) {
        return "{\"success\":" + success + ",\"summary\":\"" + summary
                + "\",\"findings\":" + findings + ",\"suggestions\":[],\"needsCodingFix\":true}";
    }
}
