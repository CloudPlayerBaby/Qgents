package qg.qgent.orchestration.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import qg.qgent.dto.ContextMemory;
import qg.qgent.dto.ContextMessage;
import qg.qgent.dto.ContextSearchResponse;
import qg.qgent.dto.ContextSkill;
import qg.qgent.service.ContextService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 运行时上下文检索工具：把现有 {@link ContextService#search}（LIKE 关键字 + 标签过滤）包装为
 * 原生函数调用，供 Agent 在运行中发现注入上下文不足以完成任务时，按需到需求群聊天与项目
 * Skill/Memory 中补取信息。
 * <p>
 * 每条 TaskRun 由 Agent 新建一个实例（仿 {@link CodingTools} 的有状态实例写法），内部维护
 * 调用计数上限 {@code maxSearches}，超限后返回 {@code ok=false} 指引模型基于现有信息完成，
 * 不重复检索。与 Workspace 工具不同，本实例不直接执行 SQL，只委托服务端 {@link ContextService}，
 * 复用其项目成员校验与既有脱敏，不触达任何 Secret 或宿主机路径。
 */
public class ContextSearchTool {

    /**
     * 每个命中类别的格式化上限，避免检索结果膨胀撑爆模型上下文。
     */
    private static final int MAX_ITEMS_PER_CATEGORY = 10;
    /**
     * 单条命中正文截断长度（字符），超出截断并追加省略号。
     */
    private static final int MAX_ITEM_CHARS = 200;
    /**
     * 每类返回条数上限，防止一次检索拉取过多记录。
     */
    private static final int MAX_LIMIT = 50;

    private final ContextService contextService;
    /**
     * 发起检索的服务端身份（来源 Task 创建者），ContextService 内部按此校验项目成员关系。
     */
    private final UUID actor;
    private final UUID projectId;
    /**
     * 需求群 ID，限定消息在群内检索；可为空（退化为项目全量消息）。
     */
    private final UUID groupId;
    /**
     * 每次 TaskRun 检索工具调用次数上限。
     */
    private final int maxSearches;
    /**
     * 本次运行已使用的检索次数。
     */
    private int searchesUsed;

    public ContextSearchTool(ContextService contextService, UUID actor, UUID projectId, UUID groupId,
                             int maxSearches) {
        this.contextService = contextService;
        this.actor = actor;
        this.projectId = projectId;
        this.groupId = groupId;
        this.maxSearches = Math.max(0, maxSearches);
    }

    /**
     * 在需求群聊天与项目 Skill/Memory 中检索上下文。
     * <p>
     * 仅当当前注入的任务/计划/历史消息缺少完成任务所需的关键信息时调用；有把握时不调用。
     * 预算耗尽后返回 {@code ok=false}，模型应基于现有上下文继续，不得反复重试。
     *
     * @param query 检索关键字，从任务/需求/历史讨论中提取；不允许为空（空关键字会退化为全量倾泻，
     *              与"非必要不检索"冲突，直接拒绝）。
     * @param tag   可选标签过滤，精确匹配 Skill/Memory 标签。
     * @param scope 检索范围：CHAT(群聊消息)/SKILL/MEMORY/ALL，默认 ALL。
     * @param limit 每类返回条数上限，默认 20、上限 50。
     * @return 结构化结果：成功含 {@code ok=true}、{@code budget}、{@code scope} 与各命中类别；
     *         失败含 {@code ok=false}、{@code error}。
     */
    @Tool(name = "search_context", description = "在需求群聊天记录与项目 Skill/Memory 中检索上下文；"
            + "仅当当前注入的上下文缺少完成任务所需的关键信息时才调用，有把握时不调用；"
            + "每次 TaskRun 有调用次数上限，预算耗尽后返回 ok=false，请基于现有信息完成")
    public Map<String, Object> searchContext(
            @ToolParam(description = "检索关键字，从任务、需求或历史讨论中提取以定位缺失信息；不允许为空") String query,
            @ToolParam(description = "可选标签过滤，精确匹配 Skill/Memory 标签", required = false) String tag,
            @ToolParam(description = "检索范围：CHAT(群聊消息)/SKILL/MEMORY/ALL，默认 ALL", required = false) String scope,
            @ToolParam(description = "每类返回条数上限，默认 20、上限 50", required = false) Integer limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (query == null || query.isBlank()) {
            return error(result, "search_context 需要非空 query，请用任务/需求中的具体关键字定位缺失的信息");
        }
        if (searchesUsed >= maxSearches) {
            return error(result, "search_context 检索预算已用尽（已调用 " + searchesUsed + "/" + maxSearches
                    + " 次），请基于现有上下文完成，不要继续检索");
        }
        searchesUsed++;
        String normalizedScope = normalizeScope(scope);
        if (normalizedScope == null) {
            return error(result, "search_context 的 scope 取值非法，只支持 CHAT/SKILL/MEMORY/ALL");
        }
        int messageLimit = Math.max(1, Math.min(limit == null ? 20 : limit, MAX_LIMIT));
        ContextSearchResponse response;
        try {
            response = contextService.search(actor, projectId, query, tag, groupId, messageLimit);
        } catch (RuntimeException e) {
            // 服务端校验或检索内部异常：作为工具级失败回灌模型，让模型修正后重试，不中断工具循环。
            return error(result, "search_context 检索失败: " + safeMessage(e));
        }
        result.put("ok", true);
        result.put("budget", "used " + searchesUsed + "/" + maxSearches);
        result.put("scope", normalizedScope);
        if (matches(normalizedScope, "CHAT")) {
            result.put("messages", formatMessages(response.getMessages()));
        }
        if (matches(normalizedScope, "SKILL")) {
            result.put("skills", formatSkills(response.getSkills()));
        }
        if (matches(normalizedScope, "MEMORY")) {
            result.put("memories", formatMemories(response.getMemories()));
        }
        return result;
    }

    private boolean matches(String scope, String category) {
        return "ALL".equals(scope) || scope.equals(category);
    }

    private List<String> formatMessages(List<ContextMessage> messages) {
        List<String> formatted = new ArrayList<>();
        for (ContextMessage message : messages) {
            if (formatted.size() >= MAX_ITEMS_PER_CATEGORY) {
                break;
            }
            StringBuilder sb = new StringBuilder("[")
                    .append(nullToBlank(message.getSenderType())).append("] ");
            if (message.getSequence() != null) {
                sb.append("seq ").append(message.getSequence()).append(": ");
            }
            sb.append(excerpt(message.getText()));
            formatted.add(sb.toString());
        }
        return formatted;
    }

    private List<String> formatSkills(List<ContextSkill> skills) {
        List<String> formatted = new ArrayList<>();
        for (ContextSkill skill : skills) {
            if (formatted.size() >= MAX_ITEMS_PER_CATEGORY) {
                break;
            }
            formatted.add(nullToBlank(skill.getName()) + ": " + excerpt(skill.getContent()));
        }
        return formatted;
    }

    private List<String> formatMemories(List<ContextMemory> memories) {
        List<String> formatted = new ArrayList<>();
        for (ContextMemory memory : memories) {
            if (formatted.size() >= MAX_ITEMS_PER_CATEGORY) {
                break;
            }
            formatted.add(nullToBlank(memory.getTitle()) + ": " + excerpt(memory.getContent()));
        }
        return formatted;
    }

    /**
     * 把正文压成单行并截断，防止把超长历史消息/规范全文灌回模型上下文。
     */
    private String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String singleLine = text.replace('\n', ' ').replace('\r', ' ').strip();
        if (singleLine.length() <= MAX_ITEM_CHARS) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_ITEM_CHARS) + "...";
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "ALL";
        }
        String upper = scope.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "CHAT", "SKILL", "MEMORY", "ALL" -> upper;
            default -> null;
        };
    }

    private Map<String, Object> error(Map<String, Object> result, String message) {
        result.put("ok", false);
        result.put("error", message);
        return result;
    }

    /**
     * 异常消息脱敏：取首行并截断，避免把完整堆栈或意外泄露的信息回灌给模型。
     */
    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "context search unavailable";
        }
        String firstLine = throwable.getMessage().strip().lines().findFirst().orElse("context search unavailable");
        return firstLine.length() <= 200 ? firstLine : firstLine.substring(0, 200);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}