package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.OrchestrationPhase;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.ToolTurnResult;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;
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
 * CodingAgent 单元测试：默认协议（native）以 mock {@link LlmClient#nextToolTurn} 驱动原生
 * Tool Calling 循环，覆盖成功收敛、多轮工具循环、工具白名单、基础设施中止、错误码分类
 * （length 截断 / 上下文超限 / 参数非法）、观测落库；legacy 手写 JSON 协议（灰度期）以 mock
 * {@link LlmClient#complete} 做少量回归。工具的真实执行与类型校验由 CodingToolsTest 覆盖。
 * 不写入任何 API Key。
 */
class CodingAgentTest {

    private static final int MAX_TOOL_ROUNDS = 20;

    private final LlmClient llm = mock(LlmClient.class);
    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
    private final UUID workspaceId = UUID.randomUUID();

    private CodingAgent nativeAgent() {
        return new CodingAgent(llm, codeAccess, writer, AgentProtocol.nativeDefault(),
                mock(ContextService.class), new ContextSearchProperties(10));
    }

    private CodingAgent legacyAgent() {
        return new CodingAgent(llm, codeAccess, writer, new AgentProtocol("legacy"),
                mock(ContextService.class), new ContextSearchProperties(10));
    }

    // ---------- 原生 Tool Calling（默认协议） ----------

    @Test
    void nativeBareFinalResultSucceeds() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn(bareResult(true, "done", "src/main/java/X.java"), "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getCodingResult().isSuccess()).isTrue();
        assertThat(outcome.getCodingResult().getSummary()).isEqualTo("done");
        assertThat(outcome.getObservations()).hasSize(1);
        assertThat(outcome.getObservations().get(0).phase()).isEqualTo("CODING");
    }

    @Test
    void nativeWrappedFinalResultSucceeds() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":" + bareResult(true, "ok", "src/main/java/X.java") + "}", "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getCodingResult().getModifiedFiles()).containsExactly("src/main/java/X.java");
    }

    @Test
    void nativeMultiRoundToolLoopExecutesToolsInOrder() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(toolTurn("read_file"),
                        toolTurn("apply_patch"),
                        finalTurn(bareResult(true, "done", "src/main/java/X.java"), "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(llm, times(3)).nextToolTurn(anyString(), anyList(), anyList());
        assertThat(outcome.getObservations()).hasSize(3);
        assertThat(outcome.getObservations().get(0).toolName()).isEqualTo("read_file");
        assertThat(outcome.getObservations().get(1).toolName()).isEqualTo("apply_patch");
        assertThat(outcome.getObservations().get(2).protocolFailureCode()).isNull();
    }

    @Test
    void nativePassesWhitelistedCodingTools() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn(bareResult(true, "done", null), "stop"));

        nativeAgent().run(codingInput());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCallback>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm).nextToolTurn(anyString(), anyList(), toolsCaptor.capture());
        List<String> names = toolsCaptor.getValue().stream()
                .map(c -> c.getToolDefinition().name()).sorted().toList();
        assertThat(names).containsExactly("activate_skill", "apply_patch", "list_files", "read_file", "search_chat_history",
                "search_code", "write_file");
    }

    @Test
    void nativeToolHistoryFlowsToNextCall() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(toolTurn("list_files"),
                        finalTurn(bareResult(true, "done", null), "stop"));

        nativeAgent().run(codingInput());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> historyCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm, times(2)).nextToolTurn(anyString(), historyCaptor.capture(), anyList());
        // 第二轮历史 = continueTools 返回的历史（非空、含首轮 user 与工具执行结果），原样回传。
        assertThat(historyCaptor.getAllValues().get(1)).hasSize(1);
        assertThat(historyCaptor.getAllValues().get(1).get(0)).isInstanceOf(UserMessage.class);
    }

    @Test
    void nativeInfraAbortMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(infraTurn("apply_patch", "workspace root is not available"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("workspace root is not available");
        verify(llm, times(1)).nextToolTurn(anyString(), anyList(), anyList());
    }

    @Test
    void nativeFinishLengthFinalizesOnceAndSucceeds() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":{\"success\":true,\"summary\":\"tr", "LENGTH"));
        when(llm.finalizeToolTurn(anyString(), anyList(), anyString()))
                .thenReturn(finalTurn(bareResult(true, "recovered", "src/main/java/X.java"), "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getCodingResult().getSummary()).isEqualTo("recovered");
        verify(llm, times(1)).finalizeToolTurn(anyString(), anyList(), anyString());
    }

    @Test
    void nativeMaxRoundsFinalizesOnceAndSucceeds() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenReturn(toolTurn("list_files"));
        when(llm.finalizeToolTurn(anyString(), anyList(), anyString()))
                .thenReturn(finalTurn(bareResult(true, "bounded finish", "src/main/java/X.java"), "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(llm, times(MAX_TOOL_ROUNDS)).nextToolTurn(anyString(), anyList(), anyList());
        verify(llm, times(1)).finalizeToolTurn(anyString(), anyList(), anyString());
        assertThat(outcome.getObservations()).hasSize(MAX_TOOL_ROUNDS + 1);
    }

    @Test
    void nativeTruncatedFinalizationKeepsOriginalLengthFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"finalResult\":", "LENGTH"));
        when(llm.finalizeToolTurn(anyString(), anyList(), anyString()))
                .thenReturn(finalTurn("{\"finalResult\":", "LENGTH"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.LLM_FINISH_LENGTH.name());
        verify(llm, times(1)).finalizeToolTurn(anyString(), anyList(), anyString());
    }

    @Test
    void nativeToolArgumentInvalidObservationIsRecorded() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(toolTurnWithCode("apply_patch", ProtocolFailureCode.LLM_TOOL_ARGUMENT_INVALID),
                        finalTurn(bareResult(true, "corrected", "src/main/java/X.java"), "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getObservations()).hasSize(2);
        assertThat(outcome.getObservations().get(0).protocolFailureCode())
                .isEqualTo(ProtocolFailureCode.LLM_TOOL_ARGUMENT_INVALID);
        // 成功轮无错误码。
        assertThat(outcome.getObservations().get(1).protocolFailureCode()).isNull();
    }

    @Test
    void nativeLlmCallFailureMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenThrow(new RuntimeException("llm boom"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("llm boom");
    }

    @Test
    void nativeMalformedFinalTextMapsToToolCallMalformed() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("this is not json", "stop"));

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED.name());
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED.name());
    }

    @Test
    void nativeMalformedJsonIsRepairedWithJsonModeCompletion() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"success\":true,\"summary\":\"将\"和\"字居中\"}", "stop"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"done\","
                        + "\"modifiedFiles\":[\"src/main/java/X.java\"]}}");

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getCodingResult().getSummary()).isEqualTo("done");
        assertThat(outcome.getObservations()).hasSize(2);
        verify(llm).complete(anyString(), anyList());
    }

    @Test
    void repairedSuccessWithoutAnyModifiedFileIsRejected() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("not json", "stop"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"未执行任何文件修改\"}}");

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED.name());
        assertThat(outcome.getMessage()).contains("requires at least one actual file modification");
    }

    @Test
    void nativeMalformedJsonRepairFailureKeepsStableFailureCode() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("not json", "stop"));
        when(llm.complete(anyString(), anyList())).thenReturn("still not json");

        AgentRunOutcome outcome = nativeAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getFailureCode()).isEqualTo(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED.name());
        assertThat(outcome.getObservations()).hasSize(2);
    }

    // ---------- legacy 手写 JSON 协议（灰度期回归） ----------

    @Test
    void legacyFinalResultSucceeds() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"finalResult\":{\"success\":true,\"summary\":\"done\",\"modifiedFiles\":[\"src/main/java/X.java\"]}}");

        AgentRunOutcome outcome = legacyAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getCodingResult().getModifiedFiles()).containsExactly("src/main/java/X.java");
    }

    @Test
    void legacyWriteFailsInfrastructureAndDoesNotFeedBack() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(writer.writeFile(workspaceId, "src/main/java/Y.java", "code"))
                .thenReturn(WorkspaceWriteResult.infraFail("src/main/java/Y.java", "workspace root is not available"));
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"toolCall\":{\"name\":\"write_file\",\"arguments\":{\"path\":\"src/main/java/Y.java\",\"content\":\"code\"}}}");

        AgentRunOutcome outcome = legacyAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("write_file infrastructure failure");
        verify(llm, times(1)).complete(anyString(), anyList());
    }

    @Test
    void legacyExceedingMaxRoundsFailsInfrastructure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.complete(anyString(), anyList()))
                .thenReturn("{\"toolCall\":{\"name\":\"list_files\"}}");

        AgentRunOutcome outcome = legacyAgent().run(codingInput());

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains(ProtocolFailureCode.LLM_CONTEXT_LIMIT.name());
        verify(llm, times(MAX_TOOL_ROUNDS)).complete(anyString(), anyList());
    }

    // ---------- 工具与观测辅助 ----------

    private AgentInput codingInput() {
        AgentInput input = new AgentInput();
        input.setProjectId(UUID.randomUUID());
        input.setTaskId(UUID.randomUUID());
        input.setTaskTitle("sample coding task");
        input.setRequirement("implement a feature");
        input.setInstruction("implement per plan");
        input.setPhase(OrchestrationPhase.CODING);
        input.setWorkspaceId(workspaceId);
        PlanResult plan = new PlanResult();
        plan.setTaskUnderstanding("understand the task");
        plan.setObjectives(List.of("goal"));
        PlanResult.ImplementationStep step = new PlanResult.ImplementationStep();
        step.setTitle("impl");
        step.setFiles(List.of("src/main/java/X.java"));
        plan.setImplementationSteps(List.of(step));
        plan.setTestPlan("run tests");
        input.setPlanResult(plan);
        return input;
    }

    private ToolTurnResult finalTurn(String json, String finishReason) {
        return ToolTurnResult.finalAnswer(json, finishReason, 20, 10, "aabb", null);
    }

    private ToolTurnResult toolTurn(String toolName) {
        return ToolTurnResult.continueTools(List.of(new UserMessage("tool executed: " + toolName)),
                "stop", 20, 10, "aabb", toolName, null);
    }

    private ToolTurnResult toolTurnWithCode(String toolName, ProtocolFailureCode code) {
        return ToolTurnResult.continueTools(List.of(new UserMessage("tool executed: " + toolName)),
                "stop", 20, 10, "aabb", toolName, code);
    }

    private ToolTurnResult infraTurn(String toolName, String reason) {
        return ToolTurnResult.infraAbort(reason, "stop", 20, 10, "aabb", toolName);
    }

    private String bareResult(boolean success, String summary, String modifiedFile) {
        StringBuilder json = new StringBuilder("{\"success\":").append(success)
                .append(",\"summary\":\"").append(summary).append("\"");
        if (modifiedFile != null) {
            json.append(",\"modifiedFiles\":[\"").append(modifiedFile).append("\"]");
        }
        return json.append("}").toString();
    }
}
