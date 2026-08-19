package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import qg.qgent.orchestration.tool.WorkspaceWriteResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NativeToolLoopSupportTest {

    @Test
    void keepsInitialUserContextAndMostRecentCompleteToolPairs() {
        UserMessage initial = new UserMessage("initial task context");
        List<Message> history = new ArrayList<>();
        history.add(initial);
        for (int i = 0; i < NativeToolLoopSupport.MAX_RECENT_TOOL_PAIRS + 3; i++) {
            history.add(assistantCall(i));
            history.add(toolResponse(i));
        }
        history.add(assistantCall(99)); // 不完整 pair 不得保留。

        List<Message> compacted = NativeToolLoopSupport.compactHistory(history);

        assertThat(compacted).hasSize(1 + NativeToolLoopSupport.MAX_RECENT_TOOL_PAIRS * 2);
        assertThat(compacted.get(0)).isSameAs(initial);
        assertThat(compacted).doesNotContain(history.get(1), history.get(2), history.get(history.size() - 1));
        assertThat(compacted.get(compacted.size() - 2)).isEqualTo(history.get(history.size() - 3));
        assertThat(compacted.get(compacted.size() - 1)).isEqualTo(history.get(history.size() - 2));
    }

    @Test
    void lateRoundAddsOneConvergenceReminderWithoutAccumulatingOldReminders() {
        List<Message> history = List.of(new UserMessage("initial"), assistantCall(1), toolResponse(1),
                new UserMessage("old reminder"));

        List<Message> prepared = NativeToolLoopSupport.prepareToolRound(history,
                NativeToolLoopSupport.CONVERGENCE_START_ROUND);

        assertThat(prepared).hasSize(4);
        assertThat(prepared.get(prepared.size() - 1)).isInstanceOf(UserMessage.class);
        assertThat(prepared.get(prepared.size() - 1).getText()).contains("收敛", "finalResult");
        assertThat(prepared).noneMatch(message -> "old reminder".equals(message.getText()));
    }

    @Test
    void earlyChangedWriteRemainsTrustedAfterItsToolPairIsCompactedAway() {
        ChangedWriteFactLedger ledger = new ChangedWriteFactLedger();
        ledger.record(WorkspaceWriteResult.ok("src/main/java/Early.java", "aabb", true));
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("initial"));
        for (int i = 0; i < NativeToolLoopSupport.MAX_RECENT_TOOL_PAIRS + 3; i++) {
            history.add(assistantCall(i));
            history.add(toolResponse(i));
        }

        List<Message> prepared = NativeToolLoopSupport.prepareToolRound(history,
                NativeToolLoopSupport.CONVERGENCE_START_ROUND, ledger.changedPaths());
        String finalInstruction = NativeToolLoopSupport.finalizationInstruction("{}", ledger.changedPaths());

        assertThat(prepared).doesNotContain(history.get(1), history.get(2));
        assertThat(prepared.get(prepared.size() - 1).getText()).contains("src/main/java/Early.java");
        assertThat(finalInstruction).contains("服务端可信写入事实", "src/main/java/Early.java");
    }

    private AssistantMessage assistantCall(int index) {
        return AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call_" + index, "function", "read_file", "{}")))
                .build();
    }

    @Test
    void failedToolResponseAddsCorrectiveGuidance() {
        List<Message> history = List.of(new UserMessage("initial"), assistantCall(1), failedToolResponse(1));

        List<Message> prepared = NativeToolLoopSupport.prepareToolRound(history, 2);

        assertThat(prepared.get(prepared.size() - 1).getText())
                .contains("errorCode、retryable 和 nextAction", "禁止原样重复失败调用");
    }

    @Test
    void repeatedFailedToolCallRequiresDifferentParameters() {
        List<Message> history = List.of(new UserMessage("initial"), assistantCall(1), failedToolResponse(1),
                assistantCall(2), failedToolResponse(2));

        List<Message> prepared = NativeToolLoopSupport.prepareToolRound(history, 3);

        assertThat(prepared.get(prepared.size() - 1).getText())
                .contains("同一工具失败已经重复一次", "禁止再次使用完全相同的工具名和参数");
    }

    private ToolResponseMessage toolResponse(int index) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call_" + index, "read_file", "result-" + index))).build();
    }

    private ToolResponseMessage failedToolResponse(int index) {
        return ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("call_" + index, "read_file",
                        "{\"ok\":false,\"errorCode\":\"TOOL_PATH_INVALID\"}"))).build();
    }
}
