package qg.qgent.orchestration.agent;

import qg.qgent.dto.ContextMemory;
import qg.qgent.dto.ContextMessage;
import qg.qgent.dto.ContextSkill;
import qg.qgent.orchestration.AgentInput;

import java.util.List;

/**
 * 把需求群标题/背景与群聊/Skill/Memory 上下文渲染进 Agent 提示词的静态纯文本工具。
 * 纯文本装配，无状态、不依赖 Spring；内容已由 {@code ContextService} 按用户+项目过滤（脱敏），不含 Secret。
 * <p>
 * 渲染格式（与后端4 交接契约对齐）：
 * <ul>
 *   <li>需求背景：{@code - 标题: xxx} / {@code - 说明: xxx}（需求群 title/description，置于最前）；</li>
 *   <li>历史消息：{@code - [senderType] text}（senderType ∈ USER/AGENT/SYSTEM），保持旧→新；</li>
 *   <li>项目规范：{@code - name: content}；</li>
 *   <li>项目约定：{@code - title: content}。</li>
 * </ul>
 * 任一上下文为空时对应段落整体省略，保证既有提示词结构不回归。
 */
final class ContextPromptRenderer {

    private ContextPromptRenderer() {
    }

    /**
     * 渲染 AgentInput 携带的需求背景与群聊/Skill/Memory 上下文；全部为空时返回空串。
     */
    static String render(AgentInput input) {
        StringBuilder sb = new StringBuilder();
        appendRequirementBackground(sb, input);
        appendConversation(sb, input.getConversation());
        appendSkills(sb, input.getSkills());
        appendMemories(sb, input.getMemories());
        return sb.toString();
    }

    private static void appendRequirementBackground(StringBuilder sb, AgentInput input) {
        String title = nullToBlank(input.getRequirementTitle());
        String description = nullToBlank(input.getRequirementDescription());
        if (title.isEmpty() && description.isEmpty()) {
            return;
        }
        sb.append("\n\n需求背景：");
        if (!title.isEmpty()) {
            sb.append("\n- 标题: ").append(title);
        }
        if (!description.isEmpty()) {
            sb.append("\n- 说明: ").append(description);
        }
    }

    private static void appendConversation(StringBuilder sb, List<ContextMessage> conversation) {
        if (conversation == null || conversation.isEmpty()) {
            return;
        }
        sb.append("\n\n历史消息：");
        for (ContextMessage message : conversation) {
            sb.append("\n- [").append(nullToBlank(message.getSenderType())).append("] ")
                    .append(nullToBlank(message.getText()));
        }
    }

    private static void appendSkills(StringBuilder sb, List<ContextSkill> skills) {
        if (skills == null || skills.isEmpty()) {
            return;
        }
        sb.append("\n\n项目规范：");
        for (ContextSkill skill : skills) {
            sb.append("\n- ").append(nullToBlank(skill.getName())).append(": ").append(nullToBlank(skill.getContent()));
        }
    }

    private static void appendMemories(StringBuilder sb, List<ContextMemory> memories) {
        if (memories == null || memories.isEmpty()) {
            return;
        }
        sb.append("\n\n项目约定：");
        for (ContextMemory memory : memories) {
            sb.append("\n- ").append(nullToBlank(memory.getTitle())).append(": ").append(nullToBlank(memory.getContent()));
        }
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
