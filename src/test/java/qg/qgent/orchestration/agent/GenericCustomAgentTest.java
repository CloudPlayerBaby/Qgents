package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import qg.qgent.entity.AgentEntity;
import qg.qgent.orchestration.AgentInput;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.OrchestrationPhase;
import qg.qgent.orchestration.RunOutcome;
import qg.qgent.orchestration.llm.LlmClient;
import qg.qgent.orchestration.llm.ToolTurnResult;
import qg.qgent.orchestration.tool.WorkspaceCodeAccess;
import qg.qgent.orchestration.tool.WorkspaceCodeWriter;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自定义 Agent 运行时单元测试：以 mock {@link LlmClient#nextToolTurn} 驱动原生 Tool Calling 循环，
 * 覆盖成功/失败结果映射（success→SUCCEEDED、!success→FAILED_QUALITY）、多轮工具循环、能力→工具
 * 白名单门禁（写能力授予 CodingTools，否则 ReviewTools）、基础设施中止与协议错误码分类。
 */
class GenericCustomAgentTest {

    private static final int MAX_TOOL_ROUNDS = 20;

    private final LlmClient llm = mock(LlmClient.class);
    private final WorkspaceCodeAccess codeAccess = mock(WorkspaceCodeAccess.class);
    private final WorkspaceCodeWriter writer = mock(WorkspaceCodeWriter.class);
    private final CapabilityToolRegistry toolRegistry = new CapabilityToolRegistry(codeAccess, writer);
    private final UUID workspaceId = UUID.randomUUID();

    private GenericCustomAgent agent(AgentEntity entity) {
        return new GenericCustomAgent(llm, codeAccess, toolRegistry, entity, null);
    }

    private AgentEntity customAgent(String... capabilities) {
        AgentEntity entity = new AgentEntity();
        entity.setId(UUID.randomUUID());
        entity.setRole("CUSTOM");
        entity.setName("My Agent");
        entity.setPrompt("you are a custom agent");
        entity.setCapabilities(List.of(capabilities));
        entity.setStatus("ACTIVE");
        return entity;
    }

    private AgentInput customInput(OrchestrationPhase phase) {
        AgentInput input = new AgentInput();
        input.setProjectId(UUID.randomUUID());
        input.setTaskId(UUID.randomUUID());
        input.setTaskRunId(UUID.randomUUID());
        input.setTaskTitle("custom task");
        input.setRequirement("do a specialized check");
        input.setInstruction("verify the subsystem");
        input.setPhase(phase);
        input.setWorkspaceId(workspaceId);
        return input;
    }

    @Test
    void successResultMapsToSucceeded() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"success\":true,\"summary\":\"done\",\"message\":\"all good\"}", "stop"));

        AgentRunOutcome outcome = agent(customAgent()).run(customInput(OrchestrationPhase.REVIEWING));

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        assertThat(outcome.getMessage()).isEqualTo("all good");
        assertThat(outcome.getObservations()).hasSize(1);
        assertThat(outcome.getObservations().get(0).phase()).isEqualTo("REVIEWING");
    }

    @Test
    void failureResultMapsToQualityFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"success\":false,\"summary\":\"found issues\",\"message\":\"security gap\"}", "stop"));

        AgentRunOutcome outcome = agent(customAgent()).run(customInput(OrchestrationPhase.REVIEWING));

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_QUALITY);
        assertThat(outcome.getMessage()).isEqualTo("security gap");
    }

    @Test
    void multiRoundToolLoopConverges() {
        when(codeAccess.listFiles(any())).thenReturn(List.of("src/main/java/X.java"));
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(toolTurn("read_file"),
                        toolTurn("search_code"),
                        finalTurn("{\"success\":true,\"summary\":\"checked\"}", "stop"));

        AgentRunOutcome outcome = agent(customAgent()).run(customInput(OrchestrationPhase.TESTING));

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.SUCCEEDED);
        verify(llm, times(3)).nextToolTurn(anyString(), anyList(), anyList());
        assertThat(outcome.getObservations()).hasSize(3);
        assertThat(outcome.getObservations().get(0).toolName()).isEqualTo("read_file");
        assertThat(outcome.getObservations().get(1).toolName()).isEqualTo("search_code");
        assertThat(outcome.getObservations().get(2).protocolFailureCode()).isNull();
    }

    @Test
    void infraAbortMapsToInfrastructureFailure() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(infraTurn("read_file", "workspace root is not available"));

        AgentRunOutcome outcome = agent(customAgent()).run(customInput(OrchestrationPhase.TESTING));

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains("workspace root is not available");
    }

    @Test
    void lengthFinishReasonMapsToLlmFinishLength() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"success\":true,\"summary\":\"tr", "length"));

        AgentRunOutcome outcome = agent(customAgent()).run(customInput(OrchestrationPhase.REVIEWING));

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains(ProtocolFailureCode.LLM_FINISH_LENGTH.name());
    }

    @Test
    void malformedFinalTextMapsToToolCallMalformed() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("this is not json", "stop"));

        AgentRunOutcome outcome = agent(customAgent()).run(customInput(OrchestrationPhase.REVIEWING));

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains(ProtocolFailureCode.LLM_TOOL_CALL_MALFORMED.name());
    }

    @Test
    void exceedingMaxRoundsFailsContextLimit() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList())).thenReturn(toolTurn("list_files"));

        AgentRunOutcome outcome = agent(customAgent()).run(customInput(OrchestrationPhase.TESTING));

        assertThat(outcome.getOutcome()).isEqualTo(RunOutcome.FAILED_INFRASTRUCTURE);
        assertThat(outcome.getMessage()).contains(ProtocolFailureCode.LLM_CONTEXT_LIMIT.name());
        verify(llm, times(MAX_TOOL_ROUNDS)).nextToolTurn(anyString(), anyList(), anyList());
        assertThat(outcome.getObservations()).hasSize(MAX_TOOL_ROUNDS);
    }

    @Test
    void writeCapabilityGrantsWriteTools() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"success\":true,\"summary\":\"done\"}", "stop"));

        agent(customAgent("coding", "implementation")).run(customInput(OrchestrationPhase.CODING));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCallback>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm).nextToolTurn(anyString(), anyList(), toolsCaptor.capture());
        List<String> names = toolsCaptor.getValue().stream()
                .map(c -> c.getToolDefinition().name()).sorted().toList();
        assertThat(names).containsExactly("apply_patch", "list_files", "read_file", "search_code", "write_file");
    }

    @Test
    void readOnlyCapabilitiesStayReadOnly() {
        when(codeAccess.listFiles(any())).thenReturn(List.of());
        when(llm.nextToolTurn(anyString(), anyList(), anyList()))
                .thenReturn(finalTurn("{\"success\":true,\"summary\":\"ok\"}", "stop"));

        agent(customAgent("read", "test")).run(customInput(OrchestrationPhase.TESTING));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ToolCallback>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(llm).nextToolTurn(anyString(), anyList(), toolsCaptor.capture());
        List<String> names = toolsCaptor.getValue().stream()
                .map(c -> c.getToolDefinition().name()).sorted().toList();
        assertThat(names).containsExactly("list_files", "read_file", "search_code");
    }

    private ToolTurnResult finalTurn(String json, String finishReason) {
        return ToolTurnResult.finalAnswer(json, finishReason, 20, 10, "aabb", null);
    }

    private ToolTurnResult toolTurn(String toolName) {
        return ToolTurnResult.continueTools(List.of(new UserMessage("tool executed: " + toolName)),
                "stop", 20, 10, "aabb", toolName, null);
    }

    private ToolTurnResult infraTurn(String toolName, String reason) {
        return ToolTurnResult.infraAbort(reason, "stop", 20, 10, "aabb", toolName);
    }
}
