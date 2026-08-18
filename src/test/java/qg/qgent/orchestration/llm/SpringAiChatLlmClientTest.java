package qg.qgent.orchestration.llm;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.support.ToolCallbacks;
import qg.qgent.orchestration.agent.ProtocolFailureCode;
import qg.qgent.orchestration.tool.WorkspaceInfraException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SpringAiChatLlmClient 契约测试：Mock ChatModel，验证 Spring AI 到 LlmClient 的转换契约。
 * <p>
 * 覆盖：纯文本 {@link #complete} 的 USER/ASSISTANT/TOOL 多消息映射与异常上抛；原生
 * {@link #nextToolTurn} 的最终文本返回、手动工具分发（结果按 tool call id 回传 ToolResponseMessage）、
 * 未知工具→LLM_TOOL_NOT_ALLOWED、基础设施异常→infraAbort、参数异常→LLM_TOOL_ARGUMENT_INVALID、
 * 每轮 options 以模型默认 options 为基底追加 toolCallbacks。全程不读取任何 API Key，不发起真实网络请求。
 */
class SpringAiChatLlmClientTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final ChatResponse response = mock(ChatResponse.class);
    private final Generation generation = mock(Generation.class);
    private final AssistantMessage output = mock(AssistantMessage.class);

    private SpringAiChatLlmClient client() {
        return new SpringAiChatLlmClient(chatModel);
    }

    /** 让 ChatModel 返回固定文本的模型输出。 */
    private void stubModelOutput(String text) {
        when(output.getText()).thenReturn(text);
        when(generation.getOutput()).thenReturn(output);
        when(response.getResult()).thenReturn(generation);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    /** 让 ChatModel 返回指定工具调用的输出（无最终文本）。 */
    private void stubToolCallOutput(List<AssistantMessage.ToolCall> calls) {
        when(output.getText()).thenReturn(null);
        when(output.getToolCalls()).thenReturn(calls);
        when(generation.getOutput()).thenReturn(output);
        ChatGenerationMetadata metadata = stopMetadata();
        when(generation.getMetadata()).thenReturn(metadata);
        when(response.getResult()).thenReturn(generation);
        when(response.hasToolCalls()).thenReturn(true);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
    }

    private void stubFinalTextOutput(String text) {
        stubModelOutput(text);
        ChatGenerationMetadata metadata = stopMetadata();
        when(generation.getMetadata()).thenReturn(metadata);
        when(response.hasToolCalls()).thenReturn(false);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
    }

    private ChatGenerationMetadata stopMetadata() {
        ChatGenerationMetadata metadata = mock(ChatGenerationMetadata.class);
        when(metadata.getFinishReason()).thenReturn("stop");
        return metadata;
    }

    private static ToolCallback tool(String name) {
        return Arrays.stream(ToolCallbacks.from(new EchoTools()))
                .filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst().orElseThrow();
    }

    // ---------- 纯文本协议（Plan/Test 与灰度期 legacy） ----------

    @Test
    void normalChatModelResponseIsReturnedAsText() {
        stubModelOutput("{\"plan\":\"json\"}");

        String result = client().complete("system prompt", "user prompt");

        assertThat(result).isEqualTo("{\"plan\":\"json\"}");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        List<Message> messages = prompt.getInstructions();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("system prompt");
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("user prompt");
        // 纯文本协议强制 JSON_OBJECT：基于默认 options 追加，保留 model/temperature/max-tokens。
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getResponseFormat().getType())
                .isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
        assertThat(options.getMaxRetries()).isZero();
    }

    @Test
    void userAssistantAndToolMessagesAreMappedToSpringMessages() {
        stubModelOutput("assistant response");
        List<LlmMessage> history = List.of(
                LlmMessage.user("user content"),
                LlmMessage.assistant("assistant content"),
                LlmMessage.tool("tool output"));

        client().complete("system prompt", history);

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        List<Message> messages = promptCaptor.getValue().getInstructions();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("system prompt");
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("user content");
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(2).getText()).isEqualTo("assistant content");
        // TOOL 结果在 JSON 协议下以 [tool result] UserMessage 呈现给模型。
        assertThat(messages.get(3)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(3).getText()).isEqualTo("[tool result]\ntool output");
    }

    @Test
    void chatModelExceptionPropagatesToCaller() {
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> client().complete("system prompt", "user prompt"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    void plainStructuredOutputLengthDoesNotReturnTruncatedText() {
        stubModelOutput("{\"summary\":\"truncated");
        ChatGenerationMetadata metadata = mock(ChatGenerationMetadata.class);
        when(metadata.getFinishReason()).thenReturn("length");
        when(generation.getMetadata()).thenReturn(metadata);
        when(response.hasToolCalls()).thenReturn(false);

        assertThatThrownBy(() -> client().complete("system prompt", "user prompt"))
                .isInstanceOf(LlmOutputTruncatedException.class)
                .hasMessageContaining("LLM_FINISH_LENGTH");
    }

    // ---------- 原生 Tool Calling（阶段 B） ----------

    @Test
    void nextToolTurnReturnsFinalAnswerWhenModelHasNoToolCalls() {
        String text = "{\"finalResult\":{\"success\":true,\"summary\":\"done\"}}";
        stubFinalTextOutput(text);

        ToolTurnResult turn = client().nextToolTurn("system prompt", List.of(new UserMessage("hi")), List.of());

        assertThat(turn.isFinalText()).isTrue();
        assertThat(turn.continuesToolLoop()).isFalse();
        assertThat(turn.text()).isEqualTo(text);
        assertThat(turn.finishReason()).isEqualTo("stop");
        assertThat(turn.promptChars()).isGreaterThan(0);
        assertThat(turn.responseChars()).isEqualTo(text.length());
        assertThat(turn.responseSha256()).isNotBlank();
        assertThat(turn.protocolFailureCode()).isNull();
    }

    @Test
    void nextToolTurnTreatsUppercaseLengthAsTruncation() {
        String text = "{\"finalResult\":{\"success\":true,\"summary\":\"tr";
        stubFinalTextOutput(text);
        ChatGenerationMetadata metadata = mock(ChatGenerationMetadata.class);
        when(metadata.getFinishReason()).thenReturn("LENGTH");
        when(generation.getMetadata()).thenReturn(metadata);

        ToolTurnResult turn = client().nextToolTurn("system prompt", List.of(new UserMessage("hi")), List.of());

        assertThat(turn.finishReason()).isEqualTo("LENGTH");
        assertThat(turn.protocolFailureCode()).isEqualTo(ProtocolFailureCode.LLM_FINISH_LENGTH);
    }

    @Test
    void nextToolTurnPrependsSystemAndPassesHistoryVerbatim() {
        stubFinalTextOutput("done");
        List<Message> history = List.of(new UserMessage("user text"), new AssistantMessage("assistant text"));

        client().nextToolTurn("system prompt", history, List.of());

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        List<Message> messages = promptCaptor.getValue().getInstructions();
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("system prompt");
        // 历史原样透传（含 assistant，保留 toolCalls/reasoning_content 元数据）。
        assertThat(messages.get(1)).isEqualTo(history.get(0));
        assertThat(messages.get(2)).isEqualTo(history.get(1));
    }

    @Test
    void nextToolTurnOptionsAreBasedOnModelOptionsAndCarryToolCallbacks() {
        stubFinalTextOutput("done");

        client().nextToolTurn("system prompt", List.of(new UserMessage("hi")), List.of(tool("echo")));

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
    }

    @Test
    void nextToolTurnExecutesToolsAndReturnsContinueHistoryWithToolResponses() {
        stubToolCallOutput(List.of(new AssistantMessage.ToolCall("call_1", "function", "echo", "{\"value\":\"hello\"}")));

        ToolTurnResult turn = client().nextToolTurn("system prompt", List.of(new UserMessage("hi")), List.of(tool("echo")));

        assertThat(turn.continuesToolLoop()).isTrue();
        assertThat(turn.isFinalText()).isFalse();
        assertThat(turn.toolName()).isEqualTo("echo");
        assertThat(turn.protocolFailureCode()).isNull();
        // history = 传入历史 + assistant toolCalls（原样）+ 按 call id 关联的 tool responses。
        assertThat(turn.history()).hasSize(3);
        assertThat(turn.history().get(0)).isInstanceOf(UserMessage.class);
        assertThat(turn.history().get(1)).isSameAs(output);
        assertThat(turn.history().get(2)).isInstanceOf(ToolResponseMessage.class);
        ToolResponseMessage toolResponse = (ToolResponseMessage) turn.history().get(2);
        assertThat(toolResponse.getResponses()).hasSize(1);
        assertThat(toolResponse.getResponses().get(0).id()).isEqualTo("call_1");
        assertThat(toolResponse.getResponses().get(0).name()).isEqualTo("echo");
        assertThat(toolResponse.getResponses().get(0).responseData()).contains("\"ok\":true", "hello");
    }

    @Test
    void nextToolTurnUnknownToolFeedsErrorBackAndFlagsNotAllowed() {
        stubToolCallOutput(List.of(new AssistantMessage.ToolCall("call_1", "function", "not_allowed", "{}")));

        ToolTurnResult turn = client().nextToolTurn("system prompt", List.of(new UserMessage("hi")), List.of(tool("echo")));

        assertThat(turn.continuesToolLoop()).isTrue();
        assertThat(turn.protocolFailureCode()).isEqualTo(ProtocolFailureCode.LLM_TOOL_NOT_ALLOWED);
        ToolResponseMessage toolResponse = (ToolResponseMessage) turn.history().get(2);
        assertThat(toolResponse.getResponses().get(0).responseData()).contains("\"ok\":false", "not_allowed");
    }

    @Test
    void nextToolTurnInfraExceptionAbortsLoop() {
        ToolCallback boom = Arrays.stream(ToolCallbacks.from(new BoomTools())).findFirst().orElseThrow();
        stubToolCallOutput(List.of(new AssistantMessage.ToolCall("call_1", "function", "boom", "{}")));

        ToolTurnResult turn = client().nextToolTurn("system prompt", List.of(new UserMessage("hi")), List.of(boom));

        assertThat(turn.isInfraAbort()).isTrue();
        assertThat(turn.continuesToolLoop()).isFalse();
        assertThat(turn.infraFailure()).contains("workspace root unavailable");
    }

    @Test
    void nextToolTurnArgumentFailureFeedsErrorBackAndFlagsArgumentInvalid() {
        ToolCallback bad = Arrays.stream(ToolCallbacks.from(new BadArgTools())).findFirst().orElseThrow();
        stubToolCallOutput(List.of(new AssistantMessage.ToolCall("call_1", "function", "bad", "{\"x\":\"y\"}")));

        ToolTurnResult turn = client().nextToolTurn("system prompt", List.of(new UserMessage("hi")), List.of(bad));

        assertThat(turn.continuesToolLoop()).isTrue();
        assertThat(turn.protocolFailureCode()).isEqualTo(ProtocolFailureCode.LLM_TOOL_ARGUMENT_INVALID);
        ToolResponseMessage toolResponse = (ToolResponseMessage) turn.history().get(2);
        assertThat(toolResponse.getResponses().get(0).responseData()).contains("\"ok\":false");
    }

    @Test
    void nextToolTurnModelFailurePropagatesAsRuntimeException() {
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("rate limited"));

        assertThatThrownBy(() -> client().nextToolTurn("system prompt", List.of(new UserMessage("hi")), List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("rate limited");
    }

    @Test
    void plainStructuredOutputUppercaseLengthDoesNotReturnTruncatedText() {
        stubModelOutput("{\"summary\":\"truncated");
        ChatGenerationMetadata metadata = mock(ChatGenerationMetadata.class);
        when(metadata.getFinishReason()).thenReturn("LENGTH");
        when(generation.getMetadata()).thenReturn(metadata);
        when(response.hasToolCalls()).thenReturn(false);

        assertThatThrownBy(() -> client().complete("system prompt", "user prompt"))
                .isInstanceOf(LlmOutputTruncatedException.class)
                .hasMessageContaining("LLM_FINISH_LENGTH");
    }

    @Test
    void finalizationUsesJsonObjectWithoutToolCallbacks() {
        String text = "{\"finalResult\":{\"success\":true,\"summary\":\"done\"}}";
        stubFinalTextOutput(text);

        ToolTurnResult result = client().finalizeToolTurn("system",
                List.of(new UserMessage("task")), "finalize now");

        assertThat(result.text()).isEqualTo(text);
        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        Prompt prompt = promptCaptor.getValue();
        assertThat(prompt.getInstructions()).hasSize(3);
        assertThat(prompt.getInstructions().get(2)).isInstanceOf(UserMessage.class);
        assertThat(prompt.getInstructions().get(2).getText()).isEqualTo("finalize now");
        OpenAiChatOptions options = (OpenAiChatOptions) prompt.getOptions();
        assertThat(options.getResponseFormat().getType())
                .isEqualTo(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT);
        assertThat(options.getMaxRetries()).isZero();
        assertThat(options.getToolCallbacks()).isEmpty();
    }

    // ---------- 测试用白名单工具 ----------

    static class EchoTools {
        @Tool(description = "echo the value back")
        public Map<String, Object> echo(@ToolParam(description = "value") String value) {
            return Map.of("ok", true, "echo", value);
        }
    }

    static class BoomTools {
        @Tool(description = "boom infra")
        public Map<String, Object> boom() {
            throw new WorkspaceInfraException("workspace root unavailable");
        }
    }

    static class BadArgTools {
        @Tool(description = "bad args")
        public Map<String, Object> bad(@ToolParam(description = "x") String x) {
            throw new IllegalArgumentException("bad argument supplied");
        }
    }
}
