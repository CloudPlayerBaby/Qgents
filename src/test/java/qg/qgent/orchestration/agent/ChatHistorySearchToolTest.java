package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import qg.qgent.dto.ContextMessage;
import qg.qgent.service.ContextService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatHistorySearchToolTest {

    private final ContextService contextService = mock(ContextService.class);
    private final UUID actor = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @Test
    void onlyQueriesCurrentGroupAndReturnsAtMostTenTruncatedMessages() {
        List<ContextMessage> messages = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            messages.add(new ContextMessage((long) index, "TEXT", "USER", "u", "x".repeat(240)));
        }
        when(contextService.searchChatHistory(actor, projectId, groupId, "登录", 50)).thenReturn(messages);
        ChatHistorySearchTool tool = new ChatHistorySearchTool(contextService, actor, projectId, groupId, 10);

        Map<String, Object> result = tool.searchChatHistory("登录", 50);

        assertThat(ToolCallbacks.from(tool)[0].getToolDefinition().name()).isEqualTo("search_chat_history");
        assertThat(result).containsEntry("ok", true).containsEntry("budget", "used 1/10");
        assertThat((List<?>) result.get("messages")).hasSize(10);
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) result.get("messages")).get(0);
        assertThat((String) first.get("text")).hasSize(203).endsWith("...");
        verify(contextService).searchChatHistory(actor, projectId, groupId, "登录", 50);
        verify(contextService, never()).activateSkill(actor, projectId, UUID.randomUUID());
    }

    @Test
    void blankQueryAndExhaustedBudgetDoNotQueryService() {
        ChatHistorySearchTool tool = new ChatHistorySearchTool(contextService, actor, projectId, groupId, 1);
        assertThat(tool.searchChatHistory(" ", null)).containsEntry("ok", false);
        when(contextService.searchChatHistory(actor, projectId, groupId, "key", 10)).thenReturn(List.of());
        assertThat(tool.searchChatHistory("key", null)).containsEntry("ok", true);
        assertThat(tool.searchChatHistory("another", null)).containsEntry("ok", false);
        verify(contextService).searchChatHistory(actor, projectId, groupId, "key", 10);
    }
}
