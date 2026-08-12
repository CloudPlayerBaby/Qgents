package qg.qgent.orchestration.llm;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SpringAiChatLlmClient 契约测试：Mock ChatModel，验证 Spring AI 到 LlmClient 的转换契约。
 * <p>
 * 覆盖：正常响应文本提取、USER/ASSISTANT/TOOL 多消息映射（TOOL 以
 * {@code [tool result]} UserMessage 呈现）、ChatModel 异常向上抛出。全程不读取任何 API Key，
 * 不发起真实网络请求。
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
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    @Test
    void normalChatModelResponseIsReturnedAsText() {
        stubModelOutput("{\"plan\":\"json\"}");

        String result = client().complete("system prompt", "user prompt");

        assertThat(result).isEqualTo("{\"plan\":\"json\"}");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        List<Message> messages = promptCaptor.getValue().getInstructions();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(messages.get(0).getText()).isEqualTo("system prompt");
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("user prompt");
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
    void singlePromptOverloadDelegatesToListVariant() {
        stubModelOutput("{\"finalResult\":{}}");

        client().complete("system prompt", "user prompt");

        ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(promptCaptor.capture());
        List<Message> messages = promptCaptor.getValue().getInstructions();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(1).getText()).isEqualTo("user prompt");
    }

    @Test
    void chatModelExceptionPropagatesToCaller() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> client().complete("system prompt", "user prompt"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("connection refused");
    }

    @Test
    void chatModelExceptionPropagatesForMultiMessageCall() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("rate limited"));

        assertThatThrownBy(() -> client().complete("system prompt", List.of(LlmMessage.user("x"))))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("rate limited");
    }
}
