package qg.qgent.orchestration.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.stereotype.Component;
import qg.qgent.orchestration.agent.ProtocolFailureCode;
import qg.qgent.orchestration.tool.Sha256;
import qg.qgent.orchestration.tool.WorkspaceInfraException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于现有 Spring AI ChatModel（OpenAI/DeepSeek starter 自动装配）的 LlmClient 实现。
 * <p>
 * {@link #complete} 两个重载保持纯文本协议（Plan/Test 与灰度期 legacy 使用）：把内部
 * {@link LlmMessage} 映射为 Spring AI 消息；TOOL 结果以 UserMessage 呈现。
 * <p>
 * {@link #nextToolTurn} 实现原生 Tool Calling（阶段 B）：手动分发工具，而不是把工具循环交给
 * Spring AI 的 {@code executeToolCalls}——因为后者把工具方法异常吞成错误响应、对未知工具名抛
 * {@code IllegalStateException}，无法区分「基础设施失败（立即中止）」与「工具级失败（回灌模型
 * 自纠）」；手动分发复用 {@link ToolCallback#call(String)}（参数解析/类型转换/结果序列化仍在
 * SDK 内），按 tool call id 回传 {@link ToolResponseMessage}，并保留 assistant 消息的
 * toolCalls 与 thinking 模式 reasoning_content 元数据原样回传（deepseek 要求，Spring AI 自动
 * 处理，勿手改）。
 * <p>
 * 每轮 options 以模型默认 options 为基底追加 toolCallbacks（{@link OpenAiChatModel#call} 不会
 * 合并 prompt options，缺省会退化为 gpt-5-mini）。复用项目既有 {@code spring.ai.openai.*} 配置，
 * 不读取或输出任何 API Key。调用失败由上层按 FAILED_INFRASTRUCTURE 处理。
 */
@Slf4j
@Component
public class SpringAiChatLlmClient implements LlmClient {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ChatModel chatModel;

    public SpringAiChatLlmClient(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String complete(String systemPrompt, String userPrompt) {
        return complete(systemPrompt, List.of(LlmMessage.user(userPrompt)));
    }

    @Override
    public String complete(String systemPrompt, String userPrompt, List<Media> media) {
        if (media == null || media.isEmpty()) {
            return complete(systemPrompt, userPrompt);
        }
        List<Message> springMessages = new ArrayList<>();
        springMessages.add(new SystemMessage(systemPrompt));
        UserMessage.Builder user = UserMessage.builder().text(userPrompt);
        user.media(media);
        springMessages.add(user.build());
        int promptChars = systemPrompt.length() + userPrompt.length();
        long started = System.nanoTime();
        String text;
        String finishReason;
        try {
            ChatResponse response = chatModel.call(new Prompt(springMessages, jsonOptions()));
            text = response.getResult().getOutput().getText();
            finishReason = finishReasonOf(response);
            String responseSha256 = text == null ? null
                    : Sha256.hex(text.getBytes(StandardCharsets.UTF_8));
            log.info("llm complete messages={} promptChars={} media={} responseChars={} durationMs={} finish={} responseSha256={}",
                    springMessages.size(), promptChars, media.size(), text == null ? 0 : text.length(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    finishReason, responseSha256);
        } catch (RuntimeException exception) {
            log.error("LLM_CALL_FAILED messages={} promptChars={} media={} category={} durationMs={}",
                    springMessages.size(), promptChars, media.size(), exception.getClass().getSimpleName(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), exception);
            throw exception;
        }
        if ("length".equalsIgnoreCase(finishReason)) {
            throw new LlmOutputTruncatedException(text == null ? 0 : text.length());
        }
        return text;
    }

    @Override
    public String complete(String systemPrompt, List<LlmMessage> messages) {
        List<Message> springMessages = new ArrayList<>();
        springMessages.add(new SystemMessage(systemPrompt));
        int promptChars = systemPrompt.length();
        for (LlmMessage message : messages) {
            springMessages.add(toSpringMessage(message));
            promptChars += message.content() == null ? 0 : message.content().length();
        }
        long started = System.nanoTime();
        String text;
        String finishReason;
        try {
            ChatResponse response = chatModel.call(new Prompt(springMessages, jsonOptions()));
            text = response.getResult().getOutput().getText();
            finishReason = finishReasonOf(response);
            String responseSha256 = text == null ? null
                    : Sha256.hex(text.getBytes(StandardCharsets.UTF_8));
            log.info("llm complete messages={} promptChars={} responseChars={} durationMs={} finish={} responseSha256={}",
                    messages.size(), promptChars, text == null ? 0 : text.length(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    finishReason, responseSha256);
        } catch (RuntimeException exception) {
            log.error("LLM_CALL_FAILED messages={} promptChars={} category={} durationMs={}",
                    messages.size(), promptChars, exception.getClass().getSimpleName(),
                    Duration.ofNanos(System.nanoTime() - started).toMillis(), exception);
            throw exception;
        }
        if ("length".equalsIgnoreCase(finishReason)) {
            throw new LlmOutputTruncatedException(text == null ? 0 : text.length());
        }
        return text;
    }

    @Override
    public ToolTurnResult nextToolTurn(String systemPrompt, List<Message> history, List<ToolCallback> tools) {
        List<Message> springMessages = new ArrayList<>();
        springMessages.add(new SystemMessage(systemPrompt));
        int promptChars = systemPrompt.length();
        for (Message message : history) {
            springMessages.add(message);
            promptChars += message.getText() == null ? 0 : message.getText().length();
        }
        OpenAiChatOptions options = nativeOptions(tools);
        long started = System.nanoTime();
        ChatResponse response = chatModel.call(new Prompt(springMessages, options));
        String finishReason = finishReasonOf(response);
        AssistantMessage output = response.getResult().getOutput();
        String text = output == null ? null : output.getText();
        int responseChars = text == null ? 0 : text.length();
        String responseSha256 = text == null ? null : Sha256.hex(text.getBytes(StandardCharsets.UTF_8));
        if (!response.hasToolCalls()) {
            ProtocolFailureCode code = "length".equalsIgnoreCase(finishReason)
                    ? ProtocolFailureCode.LLM_FINISH_LENGTH : null;
            log.info("llm toolturn final finish={} promptChars={} responseChars={} durationMs={} code={} responseSha256={}",
                    finishReason, promptChars, responseChars,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    code == null ? null : code.name(), responseSha256);
            return ToolTurnResult.finalAnswer(text, finishReason, promptChars, responseChars, responseSha256, code);
        }
        return executeTools(output, springMessages, history, tools, finishReason, promptChars, responseChars,
                responseSha256, started);
    }

    @Override
    public ToolTurnResult finalizeToolTurn(String systemPrompt, List<Message> history,
                                           String finalizationInstruction) {
        List<Message> springMessages = new ArrayList<>();
        springMessages.add(new SystemMessage(systemPrompt));
        int promptChars = systemPrompt.length();
        for (Message message : history) {
            springMessages.add(message);
            promptChars += message.getText() == null ? 0 : message.getText().length();
        }
        UserMessage instruction = new UserMessage(finalizationInstruction);
        springMessages.add(instruction);
        promptChars += finalizationInstruction.length();

        long started = System.nanoTime();
        ChatResponse response = chatModel.call(new Prompt(springMessages, jsonOptions()));
        String finishReason = finishReasonOf(response);
        AssistantMessage output = response.getResult().getOutput();
        String text = output == null ? null : output.getText();
        int responseChars = text == null ? 0 : text.length();
        String responseSha256 = text == null ? null : Sha256.hex(text.getBytes(StandardCharsets.UTF_8));
        ProtocolFailureCode code = "length".equalsIgnoreCase(finishReason)
                ? ProtocolFailureCode.LLM_FINISH_LENGTH : null;
        log.info("llm finalization finish={} promptChars={} responseChars={} durationMs={} code={} responseSha256={}",
                finishReason, promptChars, responseChars,
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                code == null ? null : code.name(), responseSha256);
        return ToolTurnResult.finalAnswer(text, finishReason, promptChars, responseChars, responseSha256, code);
    }

    /**
     * 手动分发模型请求的工具：按白名单解析回调并执行，按 tool call id 回传结果。
     * 未知工具 / 参数校验失败 → 结构化错误回灌模型自纠；基础设施失败（Workspace 不可用、
     * 文件系统错误）→ 立即中止循环。
     */
    private ToolTurnResult executeTools(AssistantMessage output, List<Message> springMessages,
                                        List<Message> incomingHistory, List<ToolCallback> tools, String finishReason,
                                        int promptChars, int responseChars, String responseSha256, long started) {
        List<Message> conversation = new ArrayList<>(incomingHistory);
        // 原样保留 assistant 消息（含 toolCalls 与 reasoning_content 元数据），供下一轮回传。
        conversation.add(output);
        String toolName = null;
        ProtocolFailureCode roundCode = null;
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall call : output.getToolCalls()) {
            toolName = toolName == null ? call.name() : toolName;
            ToolCallback callback = resolve(tools, call.name());
            if (callback == null) {
                roundCode = ProtocolFailureCode.LLM_TOOL_NOT_ALLOWED;
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                        errorJson("unknown tool '" + call.name() + "'")));
                continue;
            }
            try {
                String result = callback.call(call.arguments());
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result));
            } catch (ToolExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof WorkspaceInfraException infra) {
                    log.error("TOOL_INFRA_FAILURE tool={} durationMs={}: {}",
                            toolName, Duration.ofNanos(System.nanoTime() - started).toMillis(), infra.getMessage());
                    return ToolTurnResult.infraAbort(infra.getMessage(), finishReason, promptChars, responseChars,
                            responseSha256, toolName);
                }
                roundCode = ProtocolFailureCode.LLM_TOOL_ARGUMENT_INVALID;
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                        errorJson(safeMessage(cause))));
            } catch (RuntimeException e) {
                // 非基础设施的意外工具异常：按工具级失败回灌，不让模型循环整体中断。
                roundCode = ProtocolFailureCode.LLM_TOOL_ARGUMENT_INVALID;
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                        errorJson(safeMessage(e))));
            }
        }
        conversation.add(ToolResponseMessage.builder().responses(responses).build());
        log.info("llm toolturn tools tool={} calls={} promptChars={} responseChars={} durationMs={} code={}",
                toolName, responses.size(), promptChars, responseChars,
                Duration.ofNanos(System.nanoTime() - started).toMillis(),
                roundCode == null ? null : roundCode.name());
        return ToolTurnResult.continueTools(conversation, finishReason, promptChars, responseChars, responseSha256,
                toolName, roundCode);
    }

    /**
     * 在允许的白名单中按工具名解析回调；未知工具返回 null。
     */
    private ToolCallback resolve(List<ToolCallback> tools, String name) {
        if (name == null || tools == null) {
            return null;
        }
        for (ToolCallback tool : tools) {
            if (name.equals(tool.getToolDefinition().name())) {
                return tool;
            }
        }
        return null;
    }

    /**
     * 以模型默认 options 为基底追加工具回调。OpenAiChatModel 不会把 prompt options 与模型默认值
     * 合并，缺省会退化为 gpt-5-mini，因此必须基于默认值 mutate。
     */
    private OpenAiChatOptions nativeOptions(List<ToolCallback> tools) {
        ChatOptions defaults = chatModel.getOptions();
        if (defaults instanceof OpenAiChatOptions openAi) {
            return (OpenAiChatOptions) openAi.mutate().toolCallbacks(tools).build();
        }
        throw new IllegalStateException("ChatModel options must be OpenAiChatOptions for native tool calling; got "
                + (defaults == null ? "null" : defaults.getClass().getSimpleName()));
    }

    /**
     * 纯文本协议（Plan/Test 与灰度期 legacy）要求模型输出结构化 JSON：以模型默认 options 为基底
     * 追加 {@code response_format=json_object} 强制格式。
     * <p>
     * 注意不能用于 {@link #nextToolTurn}：native tool calling 携带 tools，DeepSeek 的 json_object
     * 模式与 tool call 互斥（开启后模型不再返回 toolCall，工具循环会整体失效），因此 JSON 强制只
     * 作用于无工具调用的纯文本路径。
     */
    private OpenAiChatOptions jsonOptions() {
        ChatOptions defaults = chatModel.getOptions();
        if (defaults instanceof OpenAiChatOptions openAi) {
            return (OpenAiChatOptions) openAi.mutate()
                    .responseFormat(OpenAiChatModel.ResponseFormat.builder()
                            .type(OpenAiChatModel.ResponseFormat.Type.JSON_OBJECT)
                            .build())
                    .toolCallbacks(List.of())
                    // 结构化调用的重试由 Agent/编排器统一控制，避免 SDK 重试造成重复 repair。
                    .maxRetries(0)
                    .build();
        }
        throw new IllegalStateException("ChatModel options must be OpenAiChatOptions for JSON mode; got "
                + (defaults == null ? "null" : defaults.getClass().getSimpleName()));
    }

    /**
     * 返回模型响应携带的结束原因（stop/length/null）；缺失时返回 null，用于判断输出是否被上限截断。
     */
    private String finishReasonOf(ChatResponse response) {
        if (response.getResult() != null && response.getResult().getMetadata() != null) {
            return response.getResult().getMetadata().getFinishReason();
        }
        return null;
    }

    /**
     * 工具级错误的结构化 JSON（ok=false），回灌模型供其自纠；不含 Secret 与宿主机路径。
     */
    private String errorJson(String message) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("ok", false);
        node.put("error", message == null ? "tool failed" : message);
        try {
            return OBJECT_MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"tool failed\"}";
        }
    }

    /**
     * 异常消息脱敏：取首行并截断，避免把完整堆栈或意外泄露的路径回灌给模型。
     */
    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "tool execution failed";
        }
        String firstLine = throwable.getMessage().strip().lines().findFirst().orElse("tool execution failed");
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200);
    }

    private Message toSpringMessage(LlmMessage message) {
        return switch (message.role()) {
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
            case TOOL -> new UserMessage("[tool result]\n" + message.content());
        };
    }
}
