package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.OrchestrationPhase;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.LlmMessage;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.orchestration.tool.DisabledWorkspaceDiffAccess;
import qg.qgent.orchestration.tool.GitDiffResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceDiffAccess;

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
 * ReviewAgent 纯单元测试：Mock LLM + Mock 只读代码访问 + Mock diff 端口，验证
 * severity 判定策略、PASS/FAIL 结果、非法输出/LLM 失败/diff 不可用的基础设施失败，
 * 以及结构上无法修改 Workspace。不启动 Spring、不访问 DB、不执行宿主机命令、不写 API Key。
 */
class ReviewAgentTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final WorkspaceDiffAccess diffAccess = mock(WorkspaceDiffAccess.class);
    private final UUID workspaceId = UUID.randomUUID();

    private ReviewAgent agent() {
        return new ReviewAgent(llm, codeAccess, diffAccess);
    }

    @Test
    void passingReviewWithNoFindingsSucceedsAndEmbedsDiffInContext() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff --git a/X.java b/X.java", "base-sha", "head-sha"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"review passed\",\"findings\":[],\"suggestions\":[],\"needsCodingFix\":false}}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getReviewResult().isSuccess()).isTrue();
        assertThat(outcome.getReviewResult().getFindings()).isEmpty();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm).complete(anyString(), captor.capture());
        String userMessage = captor.getValue().stream()
                .filter(m -> m.role() == LlmMessage.Role.USER).findFirst().orElseThrow().content();
        assertThat(userMessage)
                .contains("diff --git a/X.java b/X.java")
                .contains("base-sha")
                .contains("head-sha")
                .contains("implemented calculator")
                .contains("tests passed");
    }

    @Test
    void failingReviewWithMajorFindingAndCodingFixRequeuesQuality() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":false,\"summary\":\"major issue found\",\"findings\":"
                        + "[{\"file\":\"src/main/java/X.java\",\"line\":10,\"severity\":\"MAJOR\","
                        + "\"issue\":\"null check missing\",\"suggestion\":\"add null check\"}],"
                        + "\"suggestions\":[\"add null check\"],\"needsCodingFix\":true}}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        ReviewResult review = outcome.getReviewResult();
        assertThat(review.isSuccess()).isFalse();
        assertThat(review.getFindings()).hasSize(1);
        assertThat(review.getFindings().get(0).getSeverity()).isEqualTo("MAJOR");
        assertThat(review.getFindings().get(0).getIssue()).isEqualTo("null check missing");
        assertThat(review.isNeedsCodingFix()).isTrue();
    }

    @Test
    void blockerFindingForcesFailEvenWhenLlmClaimsSuccess() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        // LLM 声称通过，但存在 BLOCKER finding，必须强制 FAIL，不得只凭 LLM 声称。
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"looks fine\",\"findings\":"
                        + "[{\"file\":\"src/main/java/Security.java\",\"line\":5,\"severity\":\"BLOCKER\","
                        + "\"issue\":\"command injection\",\"suggestion\":\"sanitize input\"}],"
                        + "\"suggestions\":[],\"needsCodingFix\":true}}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getReviewResult().isSuccess()).isFalse();
    }

    @Test
    void majorFindingForcesFailEvenWhenLlmClaimsSuccess() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"almost done\",\"findings\":"
                        + "[{\"file\":\"src/main/java/X.java\",\"severity\":\"MAJOR\","
                        + "\"issue\":\"requirement not implemented\",\"suggestion\":\"implement it\"}],"
                        + "\"suggestions\":[],\"needsCodingFix\":true}}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getReviewResult().isSuccess()).isFalse();
    }

    @Test
    void minorOnlyFindingRespectsLlmSuccess() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"ok with minor note\",\"findings\":"
                        + "[{\"file\":\"src/main/java/X.java\",\"severity\":\"MINOR\","
                        + "\"issue\":\"method name unclear\",\"suggestion\":\"rename\"}],"
                        + "\"suggestions\":[],\"needsCodingFix\":false}}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getReviewResult().isSuccess()).isTrue();
    }

    @Test
    void infoOnlyFindingRespectsLlmSuccess() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"passed\",\"findings\":"
                        + "[{\"file\":\"src/main/java/X.java\",\"severity\":\"INFO\","
                        + "\"issue\":\"observation only\",\"suggestion\":null}],"
                        + "\"suggestions\":[],\"needsCodingFix\":false}}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
    }

    @Test
    void illegalLlmResponseMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.complete(anyString(), anyList())).thenReturn("{\"unexpected\":true}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("neither toolCall nor finalResult");
    }

    @Test
    void llmCallFailureMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.complete(anyString(), anyList())).thenThrow(new RuntimeException("llm down"));

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
    }

    @Test
    void gitDiffUnavailableMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.unavailable());

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void realDisabledDiffAccessMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        ReviewAgent disabledAgent = new ReviewAgent(llm, codeAccess, new DisabledWorkspaceDiffAccess());

        AgentRunOutcome outcome = disabledAgent.run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        verify(llm, never()).complete(anyString(), anyList());
    }

    @Test
    void reviewAgentCannotWriteWorkspaceWriteFileIsRejected() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        // LLM 试图调用 write_file，随后以无 finding 的 success 收尾。
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"toolCall\":{\"name\":\"write_file\",\"arguments\":{\"path\":\"X.java\",\"content\":\"evil\"}}}",
                        "{\"finalResult\":{\"success\":true,\"summary\":\"done\",\"findings\":[],\"suggestions\":[],\"needsCodingFix\":false}}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).complete(anyString(), captor.capture());
        String toolMessage = captor.getAllValues().get(1).stream()
                .filter(m -> m.role() == LlmMessage.Role.TOOL).findFirst().orElseThrow().content();
        assertThat(toolMessage).contains("unknown tool 'write_file'").contains("\"ok\":false");
    }

    @Test
    void blockerFindingWithoutCodingFixIsTerminalFail() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"blocked\",\"findings\":"
                        + "[{\"file\":\"src/main/java/X.java\",\"severity\":\"BLOCKER\","
                        + "\"issue\":\"cannot be auto-fixed\",\"suggestion\":\"replan\"}],"
                        + "\"suggestions\":[],\"needsCodingFix\":false}}");

        AgentRunOutcome outcome = agent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED);
        assertThat(outcome.getReviewResult().isSuccess()).isFalse();
    }

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
}
