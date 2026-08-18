package qg.qgent.orchestration.llm;

/**
 * 纯文本结构化模型输出达到 max-tokens 上限时抛出的稳定异常。
 *
 * <p>截断内容不完整，不能进入 JSON repair；上层应将其作为基础设施/协议失败处理，或退回真实执行结果。</p>
 */
public class LlmOutputTruncatedException extends RuntimeException {

    public LlmOutputTruncatedException(int responseChars) {
        super("LLM_FINISH_LENGTH: structured output truncated by max tokens, responseChars=" + responseChars);
    }
}
