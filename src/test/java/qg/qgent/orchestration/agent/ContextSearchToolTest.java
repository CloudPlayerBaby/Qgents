package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import qg.qgent.dto.ContextMemory;
import qg.qgent.dto.ContextMessage;
import qg.qgent.dto.ContextSearchResponse;
import qg.qgent.dto.ContextSkill;
import qg.qgent.service.ContextService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ContextSearchTool 单元测试：函数 schema 注册、预算门禁（超限拒绝且不再计数）、空关键字拒绝、
 * scope 子集过滤、内容截断与每类条数上限、非法 scope 拒绝、检索委托参数与异常回灌。
 * 不启动 Spring，不写 API Key；检索结果脱敏截断防上下文膨胀。
 */
class ContextSearchToolTest {

    private final ContextService contextService = mock(ContextService.class);
    private final UUID actor = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID groupId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(contextService.search(eq(actor), eq(projectId), eq("query"), eq("tag"), eq(groupId), eq(20)))
                .thenReturn(emptyResponse());
        when(contextService.search(eq(actor), eq(projectId), eq("query"), eq(null), eq(groupId), eq(20)))
                .thenReturn(emptyResponse());
        when(contextService.search(eq(actor), eq(projectId), eq("query"), eq(null), eq(groupId), eq(5)))
                .thenReturn(emptyResponse());
    }

    @Test
    void registersSearchContextFunctionSchema() {
        ToolCallback callback = ToolCallbacks.from(new ContextSearchTool(contextService, actor, projectId, groupId, 10))[0];
        assertThat(callback.getToolDefinition().name()).isEqualTo("search_context");
        assertThat(callback.getToolDefinition().description()).isNotBlank();
    }

    @Test
    void blankQueryIsRejectedWithoutConsumingBudget() {
        Map<String, Object> result = tool(10).searchContext("   ", null, "CHAT", null);

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("非空 query");
        verify(contextService, never()).search(actor, projectId, "   ", null, groupId, 20);
    }

    @Test
    void budgetIsCappedPerRun() {
        ContextSearchTool tool = tool(2);
        tool.searchContext("query", null, null, null);
        tool.searchContext("query", null, null, null);

        Map<String, Object> exhausted = tool.searchContext("query", null, null, null);

        assertThat(exhausted.get("ok")).isEqualTo(false);
        assertThat((String) exhausted.get("error")).contains("预算已用尽");
        assertThat((String) exhausted.get("error")).contains("2/2");
        // 成功后继续检索的次数仍然是 2 次，不再委托。
        verify(contextService, org.mockito.Mockito.times(2)).search(eq(actor), eq(projectId), eq("query"),
                eq(null), eq(groupId), eq(20));
    }

    @Test
    void successReportsBudgetUsage() {
        Map<String, Object> result = tool(5).searchContext("query", null, null, null);

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("budget")).isEqualTo("used 1/5");
    }

    @Test
    void scopeChatReturnsOnlyMessages() {
        ContextMessage message = new ContextMessage(12L, "TEXT", "USER", null, "thread says pay-then-fulfill");
        when(contextService.search(actor, projectId, "query", null, groupId, 20))
                .thenReturn(new ContextSearchResponse(
                        List.of(new ContextSkill("pay-skill", "spec")),
                        List.of(new ContextMemory("memo", "note", "ARCH")),
                        List.of(message)));

        Map<String, Object> result = tool(5).searchContext("query", null, "CHAT", null);

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result.get("scope")).isEqualTo("CHAT");
        assertThat(result).doesNotContainKey("skills");
        assertThat(result).doesNotContainKey("memories");
        assertThat(((List<?>) result.get("messages"))).hasSize(1);
    }

    @Test
    void scopeAllReturnsAllCategories() {
        when(contextService.search(actor, projectId, "query", null, groupId, 20))
                .thenReturn(new ContextSearchResponse(
                        List.of(new ContextSkill("pay-skill", "spec")),
                        List.of(new ContextMemory("memo", "note", "ARCH")),
                        List.of(new ContextMessage(1L, "TEXT", "USER", null, "hi"))));

        Map<String, Object> result = tool(5).searchContext("query", null, "ALL", null);

        assertThat(result.get("ok")).isEqualTo(true);
        assertThat(result).containsKey("skills");
        assertThat(result).containsKey("memories");
        assertThat(result).containsKey("messages");
    }

    @Test
    void invalidScopeIsRejected() {
        Map<String, Object> result = tool(5).searchContext("query", null, "DOCS", null);

        assertThat(result.get("ok")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("scope 取值非法");
    }

    @Test
    void longContentIsTruncatedToSingleLine() {
        String longText = "话".repeat(300);
        when(contextService.search(actor, projectId, "query", null, groupId, 20))
                .thenReturn(new ContextSearchResponse(List.of(), List.of(),
                        List.of(new ContextMessage(1L, "TEXT", "USER", null, longText))));

        Map<String, Object> result = tool(5).searchContext("query", null, "CHAT", null);

        String rendered = ((List<String>) result.get("messages")).get(0);
        assertThat(rendered).doesNotContain("\n");
        // 正文被截断到 200 字符并追加省略号，整体长度显著小于未截断时的 300+ 前缀长度。
        assertThat(rendered).endsWith("...");
        assertThat(rendered.length()).isLessThan(300);
    }

    @Test
    void categoryItemsAreCapped() {
        List<ContextSkill> many = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(new ContextSkill("skill-" + i, "spec-" + i));
        }
        when(contextService.search(actor, projectId, "query", null, groupId, 20))
                .thenReturn(new ContextSearchResponse(many, List.of(), List.of()));

        Map<String, Object> result = tool(5).searchContext("query", null, "SKILL", null);

        assertThat(((List<?>) result.get("skills"))).hasSize(10);
    }

    @Test
    void delegatesActorProjectGroupAndClampedLimit() {
        when(contextService.search(actor, projectId, "query", "tag", groupId, 5))
                .thenReturn(emptyResponse());
        ContextSearchTool tool = tool(5);

        tool.searchContext("query", "tag", null, 5);

        verify(contextService).search(actor, projectId, "query", "tag", groupId, 5);
    }

    @Test
    void backendFailureIsReturnedAsToolError() {
        when(contextService.search(actor, projectId, "query", null, groupId, 20))
                .thenThrow(new RuntimeException("boom"));
        Map<String, Object> first = tool(5).searchContext("query", null, null, null);

        assertThat(first.get("ok")).isEqualTo(false);
        assertThat((String) first.get("error")).contains("boom");
    }

    private ContextSearchTool tool(int max) {
        return new ContextSearchTool(contextService, actor, projectId, groupId, max);
    }

    private ContextSearchResponse emptyResponse() {
        return new ContextSearchResponse(List.of(), List.of(), List.of());
    }
}