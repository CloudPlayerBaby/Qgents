package qg.qgent.orchestration.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于现有 Spring AI ChatModel（OpenAI/DeepSeek starter 自动装配）的 LlmClient 实现。
 * <p>
 * 复用项目既有 {@code spring.ai.openai.*} 配置（base-url、model、response-format: JSON_OBJECT），
 * 不引入新 LLM 框架，也不读取或输出任何 API Key。多消息重载把内部 {@link LlmMessage}
 * 映射为 Spring AI 消息类型；TOOL 结果在 JSON 协议下以 UserMessage 呈现给模型。
 * 调用失败由上层按 FAILED_INFRASTRUCTURE 处理。
 */
@Component
public class SpringAiChatLlmClient implements LlmClient {

    private final ChatModel chatModel;

    public SpringAiChatLlmClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, List.of(LlmMessage.user(userPrompt)));
    }

    @Override
    public String complete(String systemPrompt, List<LlmMessage> messages) {
        List<Message> springMessages = new ArrayList<>();
        springMessages.add(new SystemMessage(systemPrompt));
        for (LlmMessage message : messages) {
            springMessages.add(toSpringMessage(message));
        }
        ChatResponse response = chatModel.call(new Prompt(springMessages));
        return response.getResult().getOutput().getText();
    }

    private Message toSpringMessage(LlmMessage message) {
        return switch (message.role()) {
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
            case TOOL -> new UserMessage("[tool result]\n" + message.content());
        };
    }
}
