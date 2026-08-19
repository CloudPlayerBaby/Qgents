package qg.qgent.orchestration.agent;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import qg.qgent.orchestration.llm.ToolTurnResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生工具循环的有界历史与收敛提示。只压缩发送给模型的历史副本，不修改真实工具结果或持久化事实。
 */
public final class NativeToolLoopSupport {

    static final int MAX_RECENT_TOOL_PAIRS = 4;
    static final int CONVERGENCE_START_ROUND = 16;

    private NativeToolLoopSupport() {
    }

    /**
     * 保留首条用户任务上下文与最近的完整 assistant tool-call / tool-response 对。
     */
    static List<Message> compactHistory(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        Message initial = history.stream().filter(UserMessage.class::isInstance).findFirst().orElse(history.get(0));
        List<List<Message>> pairs = new ArrayList<>();
        for (int i = 0; i + 1 < history.size(); i++) {
            Message assistant = history.get(i);
            Message response = history.get(i + 1);
            if (assistant instanceof AssistantMessage assistantMessage
                    && assistantMessage.hasToolCalls()
                    && response instanceof ToolResponseMessage) {
                pairs.add(List.of(assistant, response));
                i++;
            }
        }
        int from = Math.max(0, pairs.size() - MAX_RECENT_TOOL_PAIRS);
        List<Message> compacted = new ArrayList<>();
        compacted.add(initial);
        for (int i = from; i < pairs.size(); i++) {
            compacted.addAll(pairs.get(i));
        }
        return List.copyOf(compacted);
    }

    /**
     * 后五轮附加一次性收敛提醒；旧提醒不会进入下一轮压缩历史。
     */
    static List<Message> prepareToolRound(List<Message> history, int round) {
        return prepareToolRound(history, round, List.of());
    }

    static List<Message> prepareToolRound(List<Message> history, int round, List<String> changedPaths) {
        return prepareToolRound(history, round, changedPaths, List.of());
    }

    static List<Message> prepareToolRound(List<Message> history, int round, List<String> changedPaths,
                                          List<String> changedDirectories) {
        List<Message> compacted = new ArrayList<>(compactHistory(history));
        appendToolFailureGuidance(compacted);
        if (round >= CONVERGENCE_START_ROUND) {
            compacted.add(new UserMessage("工具轮次即将达到上限，请立即收敛：仅在仍缺少关键证据时调用一个必要工具；"
                    + "证据充分时停止调用工具并输出契约要求的 finalResult JSON。"
                    + renderChangedWriteFacts(changedPaths, changedDirectories)));
        }
        return List.copyOf(compacted);
    }

    /**
     * 工具返回错误时显式告诉模型下一步，避免模型把同一个非法调用原样重放。
     * 错误响应本身仍保留在历史中，提醒只是短暂的当前轮指令，不会污染后续压缩历史。
     */
    private static void appendToolFailureGuidance(List<Message> messages) {
        if (messages.size() < 2 || !(messages.get(messages.size() - 1) instanceof ToolResponseMessage response)) {
            return;
        }
        boolean failed = response.getResponses().stream()
                .anyMatch(item -> item.responseData() != null && item.responseData().contains("\"ok\":false"));
        if (!failed) {
            return;
        }
        boolean repeated = messages.size() >= 4
                && messages.get(messages.size() - 4) instanceof AssistantMessage previousAssistant
                && messages.get(messages.size() - 3) instanceof ToolResponseMessage previousResponse
                && messages.get(messages.size() - 2) instanceof AssistantMessage currentAssistant
                && previousAssistant.hasToolCalls() && currentAssistant.hasToolCalls()
                && hasSameToolCalls(previousAssistant, currentAssistant)
                && hasSameErrorResponses(previousResponse, response);
        String guidance = repeated
                ? "同一工具失败已经重复一次。禁止再次使用完全相同的工具名和参数；请改用错误中的 nextAction，或停止调用工具并输出 success=false。"
                : "上一轮工具返回 ok=false。请读取 errorCode、retryable 和 nextAction，修正参数后再调用；禁止原样重复失败调用。"
                + "路径、权限或不允许的工具错误不可通过重试绕过。";
        messages.add(new UserMessage(guidance));
    }

    private static boolean hasSameToolCalls(AssistantMessage first, AssistantMessage second) {
        List<AssistantMessage.ToolCall> firstCalls = first.getToolCalls();
        List<AssistantMessage.ToolCall> secondCalls = second.getToolCalls();
        if (firstCalls.size() != secondCalls.size()) {
            return false;
        }
        for (int i = 0; i < firstCalls.size(); i++) {
            AssistantMessage.ToolCall left = firstCalls.get(i);
            AssistantMessage.ToolCall right = secondCalls.get(i);
            if (!java.util.Objects.equals(left.name(), right.name())
                    || !java.util.Objects.equals(left.arguments(), right.arguments())) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSameErrorResponses(ToolResponseMessage first, ToolResponseMessage second) {
        List<ToolResponseMessage.ToolResponse> firstResponses = first.getResponses();
        List<ToolResponseMessage.ToolResponse> secondResponses = second.getResponses();
        if (firstResponses.size() != secondResponses.size()) {
            return false;
        }
        for (int i = 0; i < firstResponses.size(); i++) {
            ToolResponseMessage.ToolResponse left = firstResponses.get(i);
            ToolResponseMessage.ToolResponse right = secondResponses.get(i);
            if (!java.util.Objects.equals(left.name(), right.name())
                    || !java.util.Objects.equals(left.responseData(), right.responseData())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构造最终归纳使用的有界历史；截断的最终文本只作为未完成证据附在末尾。
     */
    static List<Message> prepareFinalization(List<Message> requestHistory, ToolTurnResult trigger) {
        List<Message> source = trigger.continuesToolLoop() ? trigger.history() : requestHistory;
        List<Message> compacted = new ArrayList<>(compactHistory(source));
        if (trigger.isFinalText() && trigger.text() != null && !trigger.text().isBlank()) {
            compacted.add(new AssistantMessage(trigger.text()));
        }
        return List.copyOf(compacted);
    }

    static String finalizationInstruction(String schema) {
        return finalizationInstruction(schema, List.of());
    }

    static String finalizationInstruction(String schema, List<String> changedPaths) {
        return finalizationInstruction(schema, changedPaths, List.of());
    }

    static String finalizationInstruction(String schema, List<String> changedPaths,
                                          List<String> changedDirectories) {
        return "停止调用工具。仅依据以上任务上下文、已有工具结果和已发生的真实操作，执行一次最终归纳。"
                + "不得声称未实际发生的写入、测试或审查结论；信息不足时按契约返回失败。"
                + renderChangedWriteFacts(changedPaths, changedDirectories)
                + "只输出一个 JSON 对象，不要代码围栏或说明文字。结构：" + schema;
    }

    private static String renderChangedWriteFacts(List<String> changedPaths) {
        return renderChangedWriteFacts(changedPaths, List.of());
    }

    private static String renderChangedWriteFacts(List<String> changedPaths, List<String> changedDirectories) {
        List<String> files = changedPaths == null ? List.of() : changedPaths.stream()
                .filter(path -> path != null && !path.isBlank())
                .limit(ChangedWriteFactLedger.MAX_CHANGED_PATHS)
                .toList();
        List<String> directories = changedDirectories == null ? List.of() : changedDirectories.stream()
                .filter(path -> path != null && !path.isBlank())
                .limit(ChangedWriteFactLedger.MAX_CHANGED_PATHS)
                .toList();
        if (files.isEmpty() && directories.isEmpty()) {
            return "\n服务端可信写入事实：本次运行尚未观测到 changed=true 的写操作。\n";
        }
        StringBuilder facts = new StringBuilder("\n服务端可信写入事实：");
        if (!files.isEmpty()) {
            facts.append("已修改文件=").append(String.join("、", files));
        }
        if (!directories.isEmpty()) {
            if (!files.isEmpty()) {
                facts.append("；");
            }
            facts.append("已新建目录=").append(String.join("、", directories));
        }
        return facts.append("。\n").toString();
    }
}
