package qg.qgent.orchestration.llm;

import org.springframework.ai.chat.messages.Message;
import qg.qgent.orchestration.agent.ProtocolFailureCode;

import java.util.List;

/**
 * 一轮原生工具调用的结果（阶段 B）。三个互斥状态：
 * <ul>
 *   <li>模型给出最终文本（{@link #text()} 非空）：Agent 解析 finalResult 收敛，不继续循环；</li>
 *   <li>模型请求工具且已执行完成（{@link #history()} 非空）：Agent 把历史原样回传继续循环；
 *       history 不含 system 消息，下一轮由客户端重新前置 system；</li>
 *   <li>工具执行遇到基础设施级失败（{@link #infraFailure()} 非空）：Agent 应立即中止并映射
 *       FAILED_INFRASTRUCTURE，不进入模型纠正循环。</li>
 * </ul>
 * 每轮附带脱敏观测字段（promptChars/responseChars/finishReason/toolName/protocolFailureCode/
 * responseSha256），供 {@link LlmObservation} 落库。
 */
public record ToolTurnResult(
        String text,
        List<Message> history,
        String infraFailure,
        String finishReason,
        int promptChars,
        int responseChars,
        String responseSha256,
        String toolName,
        ProtocolFailureCode protocolFailureCode) {

    /**
     * 模型输出了最终文本（未请求工具）。
     */
    public static ToolTurnResult finalAnswer(String text, String finishReason, int promptChars, int responseChars,
                                             String responseSha256, ProtocolFailureCode protocolFailureCode) {
        return new ToolTurnResult(text, null, null, finishReason, promptChars, responseChars,
                responseSha256, null, protocolFailureCode);
    }

    /**
     * 模型请求工具且已执行完成，history 为下一轮完整对话（不含 system）。
     */
    public static ToolTurnResult continueTools(List<Message> history, String finishReason, int promptChars,
                                               int responseChars, String responseSha256, String toolName,
                                               ProtocolFailureCode protocolFailureCode) {
        return new ToolTurnResult(null, List.copyOf(history), null, finishReason, promptChars, responseChars,
                responseSha256, toolName, protocolFailureCode);
    }

    /**
     * 工具执行遇到基础设施级失败，Agent 应立即中止。
     */
    public static ToolTurnResult infraAbort(String infraFailure, String finishReason, int promptChars,
                                            int responseChars, String responseSha256, String toolName) {
        return new ToolTurnResult(null, null, infraFailure, finishReason, promptChars, responseChars,
                responseSha256, toolName, null);
    }

    /**
     * 是否输出最终文本（本轮结束）。
     */
    public boolean isFinalText() {
        return text != null;
    }

    /**
     * 是否已执行工具、应继续循环。
     */
    public boolean continuesToolLoop() {
        return history != null;
    }

    /**
     * 是否基础设施级失败、应中止。
     */
    public boolean isInfraAbort() {
        return text == null && history == null && infraFailure != null;
    }
}
