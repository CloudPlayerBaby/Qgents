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
    private final ContextService contextService = mock(ContextService.class);
    private final UUID workspaceId = UUID.randomUUID();

    private ReviewAgent nativeAgent() {
        return new ReviewAgent(llm, codeAccess, diffAccess, AgentProtocol.nativeDefault(),
                contextService, new ContextSearchProperties(10));
    }

    private ReviewAgent legacyAgent() {
        return new ReviewAgent(llm, codeAccess, diffAccess, new AgentProtocol("legacy"),
                contextService, new ContextSearchProperties(10));
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
    void nativeReviewRecordsActuallyActivatedSkillsForQualityRepair() {
        UUID skillId = UUID.randomUUID();
        qg.qgent.entity.SkillEntity skill = new qg.qgent.entity.SkillEntity();
        skill.setName("README 规范");
        skill.setContent("末行签名");
        AgentInput input = input();
        when(contextService.activateSkill(input.getActorId(), input.getProjectId(), skillId)).thenReturn(skill);
        when(codeAccess.listFiles(any())).thenReturn(List.of("README.md"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        java.util.concurrent.atomic.AtomicInteger round = new java.util.concurrent.atomic.AtomicInteger();
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            if (round.getAndIncrement() == 0) {
                @SuppressWarnings("unchecked")
                List<org.springframework.ai.tool.ToolCallback> callbacks = invocation.getArgument(2);
                String response = callbacks.stream().filter(callback -> "activate_skill".equals(callback.getToolDefinition().name()))
                        .findFirst().orElseThrow().call("{\"skillId\":\"" + skillId + "\"}");
                assertThat(response).contains("\"ok\":true");
                return toolTurn("activate_skill");
            }
            return finalTurn(reviewJson(false, "missing skill requirement",
                    "[{\"file\":\"README.md\",\"severity\":\"MAJOR\",\"issue\":\"missing\",\"suggestion\":\"fix\"}]"));
        });

        AgentRunOutcome outcome = nativeAgent().run(input);

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getActivatedSkillIds()).containsExactly(skillId);
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
    void nativeMissingAcceptanceTargetCarriesStableFailureCode() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        // 验收目标（DOM 选择器等）经核实不存在：Review 不得臆造目标存在，必须输出
        // REVIEW_ASSERTION_TARGET_NOT_FOUND 稳定码且 needsCodingFix=true 回到 Coding 补齐。
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":{\"success\":false,\"summary\":\"验收目标不存在\","
                        + "\"findings\":[{\"file\":\"src/main/resources/template.html\",\"severity\":\"MAJOR\","
                        + "\"issue\":\"页面缺少任务要求的 #submit 按钮\",\"suggestion\":\"补齐该按钮\"}],"
                        + "\"suggestions\":[],\"needsCodingFix\":true,"
                        + "\"failureCode\":\"REVIEW_ASSERTION_TARGET_NOT_FOUND\"}}"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getReviewResult().isSuccess()).isFalse();
        assertThat(outcome.getReviewResult().getFailureCode()).isEqualTo("REVIEW_ASSERTION_TARGET_NOT_FOUND");
        assertThat(outcome.getReviewResult().isNeedsCodingFix()).isTrue();
        // 稳定失败码必须传播到 outcome，供 Run/任务级失败语义区分「验收目标缺失」。
        assertThat(outcome.getFailureCode()).isEqualTo("REVIEW_ASSERTION_TARGET_NOT_FOUND");
    }

    @Test
    void nativeUnknownFailureCodeIsIgnoredNotLeaked() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":{\"success\":false,\"summary\":\"boom\","
                        + "\"findings\":[{\"file\":\"src/main/java/X.java\",\"severity\":\"MAJOR\","
                        + "\"issue\":\"missing check\",\"suggestion\":\"add check\"}],"
                        + "\"suggestions\":[],\"needsCodingFix\":true,"
                        + "\"failureCode\":\"MODEL_INVENTED_INTERNAL_CODE\"}}"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        // 白名单外的失败码被忽略，避免把模型臆造的内部细节作为公开码外泄。
        assertThat(outcome.getReviewResult().getFailureCode()).isNull();
        assertThat(outcome.getFailureCode()).isNull();
    }

    @Test
    void legacyMissingAcceptanceTargetCarriesStableFailureCode() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":false,\"summary\":\"目标不存在\","
                        + "\"findings\":[{\"file\":\"src/main/java/X.java\",\"severity\":\"MAJOR\","
                        + "\"issue\":\"接口方法缺失\",\"suggestion\":\"补齐\"}],"
                        + "\"suggestions\":[],\"needsCodingFix\":true,"
                        + "\"failureCode\":\"REVIEW_ASSERTION_TARGET_NOT_FOUND\"}}");

        AgentRunOutcome outcome = legacyAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getReviewResult().getFailureCode()).isEqualTo("REVIEW_ASSERTION_TARGET_NOT_FOUND");
        assertThat(outcome.getFailureCode()).isEqualTo("REVIEW_ASSERTION_TARGET_NOT_FOUND");
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
    void nativeMinorOnlyPassesEvenWhenLlmClaimsFailed() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        // LLM 保守写 success=false，但只有 MINOR finding → 服务端强制 PASS（R-J）。
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":{\"success\":false,\"summary\":\"minor note\","
                        + "\"findings\":[{\"file\":\"src/main/java/X.java\",\"severity\":\"MINOR\","
                        + "\"issue\":\"method name unclear\",\"suggestion\":\"rename\"}],"
                        + "\"suggestions\":[],\"needsCodingFix\":true}}"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getReviewResult().isSuccess()).isTrue();
    }

    @Test
    void nativeEmptyFindingsPassEvenWhenLlmClaimsFailed() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        // 零 finding + success=false → 无可修之物，直接 PASS，消除空转。
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":{\"success\":false,\"summary\":\"hesitant\","
                        + "\"findings\":[],\"suggestions\":[],\"needsCodingFix\":true}}"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getReviewResult().isSuccess()).isTrue();
        assertThat(outcome.getReviewResult().getFindings()).isEmpty();
    }

    @Test
    void nativeMinorWithCorrectnessWordPassesSinceLenient() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        // 宽松版不升格：MINOR 即使提到空指针也放行，即使 LLM 声称通过也仍是 PASS。
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":{\"success\":true,\"summary\":\"ok\","
                        + "\"findings\":[{\"file\":\"src/main/java/X.java\",\"severity\":\"MINOR\","
                        + "\"issue\":\"null check missing\",\"suggestion\":\"add null check\"}],"
                        + "\"suggestions\":[],\"needsCodingFix\":true}}"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getReviewResult().isSuccess()).isTrue();
        assertThat(outcome.getReviewResult().getFindings().get(0).getSeverity()).isEqualTo("MINOR");
    }

    @Test
    void nativeMajorStyleFindingPassesWithNormalizedSeverity() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        // MAJOR 但纯风格（unused import）→ 降级 MINOR → PASS，findings 严重度同步归一化。
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":{\"success\":false,\"summary\":\"style only\","
                        + "\"findings\":[{\"file\":\"src/main/java/X.java\",\"severity\":\"MAJOR\","
                        + "\"issue\":\"unused import\",\"suggestion\":\"remove it\"}],"
                        + "\"suggestions\":[],\"needsCodingFix\":true}}"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getReviewResult().isSuccess()).isTrue();
        assertThat(outcome.getReviewResult().getFindings().get(0).getSeverity()).isEqualTo("MINOR");
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
    void nativePlainTextFinalIsRepairedOnce() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("我已完成对本次改动的审查，未发现问题。"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn(reviewJson(true, "review repaired", "[]"));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getReviewResult().getSummary()).isEqualTo("review repaired");
        assertThat(outcome.getObservations()).hasSize(2);
        verify(llm, times(1)).complete(anyString(), anyList());
    }

    @Test
    void nativeRepairThatIsStillInvalidRemainsInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("我完成了审查，但无法按格式输出。"));
        when(llm.complete(anyString(), anyList())).thenReturn("仍然不是 JSON");

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED.name());
        assertThat(outcome.getObservations()).hasSize(2);
        verify(llm, times(1)).complete(anyString(), anyList());
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
    void nativeFinishLengthFinalizesOnceAndSucceeds() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurnWithReason("{\"finalResult\":{\"success\":true,\"summary\":\"tr", "LENGTH"));
        when(llm.finalizeToolTurn(anyString(), anyList(), anyString()))
                .thenReturn(finalTurn(reviewJson(true, "recovered", "[]")));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(llm, times(1)).finalizeToolTurn(anyString(), anyList(), anyString());
    }

    @Test
    void nativeMaxRoundsFinalizesOnceAndSucceeds() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("pom.xml"));
        when(diffAccess.diff(any())).thenReturn(GitDiffResult.ok("diff", "base", "head"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(toolTurn("read_file"));
        when(llm.finalizeToolTurn(anyString(), anyList(), anyString()))
                .thenReturn(finalTurn(reviewJson(true, "bounded finish", "[]")));

        AgentRunOutcome outcome = nativeAgent().run(input());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(llm, times(MAX_TOOL_ROUNDS)).nextToolTurn(anyString(), anyList(), anyList());
        verify(llm, times(1)).finalizeToolTurn(anyString(), anyList(), anyString());
        assertThat(outcome.getObservations()).hasSize(MAX_TOOL_ROUNDS + 1);
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
        input.setActorId(UUID.randomUUID());
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
