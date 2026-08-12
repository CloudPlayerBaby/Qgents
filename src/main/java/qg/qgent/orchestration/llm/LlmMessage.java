package qg.qgent.orchestration.llm;

/**
 * 多轮工具调用场景下的单条对话消息：USER / ASSISTANT（模型输出）/ TOOL（工具执行结果）。
 * <p>
 * 工具调用采用结构化 JSON 协议：模型输出 {@code {"toolCall":{name,arguments}}} 或
 * {@code {"finalResult":{...}}}，工具结果以 TOOL 消息回灌历史，供模型继续决策。
 * 该模型不承载 Secret，也不会被序列化到日志。
 */
public record LlmMessage(Role role, String content) {

    public enum Role {
        USER,
        ASSISTANT,
        TOOL
    }

    public static LlmMessage user(String content) {
        return new LlmMessage(Role.USER, content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage(Role.ASSISTANT, content);
    }

    public static LlmMessage tool(String content) {
        return new LlmMessage(Role.TOOL, content);
    }
}
