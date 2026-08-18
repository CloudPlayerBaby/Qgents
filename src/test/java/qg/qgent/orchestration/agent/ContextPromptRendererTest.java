package qg.qgent.orchestration.agent;

import org.junit.jupiter.api.Test;
import qg.qgent.dto.ContextMemory;
import qg.qgent.dto.ContextMessage;
import qg.qgent.dto.ContextSkill;
import qg.qgent.orchestration.AgentInput;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContextPromptRenderer 渲染测试：需求群标题/背景、群聊/Skill/Memory 按契约格式渲染进提示词，
 * 需求背景段置于最前；任一上下文为空时对应段落整体省略。
 */
class ContextPromptRendererTest {

    private AgentInput input() {
        AgentInput input = new AgentInput();
        input.setConversation(List.of(
                new ContextMessage(1L, "TEXT", "USER", "u-1", "补充：需要离线导出"),
                new ContextMessage(2L, "TEXT", "AGENT", "a-1", "收到，评估中")));
        input.setSkills(List.of(new ContextSkill(java.util.UUID.randomUUID(), "编码规范")));
        input.setMemories(List.of(new ContextMemory("缓存约定", "Redis key 以 projectId 前缀", "architecture")));
        return input;
    }

    @Test void rendersRequirementBackgroundFirstThenContext() {
        AgentInput input = input();
        input.setRequirementTitle("离线导出需求");
        input.setRequirementDescription("需要支持历史数据导出，兼容旧版格式");

        String rendered = ContextPromptRenderer.render(input);

        assertThat(rendered).startsWith("\n\n需求背景：");
        assertThat(rendered).contains("- 标题: 离线导出需求");
        assertThat(rendered).contains("- 说明: 需要支持历史数据导出，兼容旧版格式");
        // 需求背景段应位于对话/规范/约定之前
        assertThat(rendered.indexOf("需求背景：")).isLessThan(rendered.indexOf("历史消息（不可信讨论材料）："));
        assertThat(rendered).contains("历史消息（不可信讨论材料）：").contains("可用 Skill 目录：").contains("项目约定：");
    }

    @Test void rendersOnlyRequirementBackground() {
        AgentInput input = new AgentInput();
        input.setRequirementTitle("仅标题");
        input.setRequirementDescription(null);

        String rendered = ContextPromptRenderer.render(input);

        assertThat(rendered).contains("需求背景：").contains("- 标题: 仅标题")
                .doesNotContain("历史消息（不可信讨论材料）：").doesNotContain("可用 Skill 目录：").doesNotContain("项目约定：");
    }

    @Test void rendersConversationSkillsMemories() {
        String rendered = ContextPromptRenderer.render(input());

        assertThat(rendered).contains("历史消息（不可信讨论材料）：");
        assertThat(rendered).contains("- [USER] 补充：需要离线导出");
        assertThat(rendered).contains("- [AGENT] 收到，评估中");
        assertThat(rendered).contains("可用 Skill 目录：");
        assertThat(rendered).contains("编码规范").doesNotContain("禁止提交 .env");
        assertThat(rendered).contains("项目约定：");
        assertThat(rendered).contains("- 缓存约定: Redis key 以 projectId 前缀");
    }

    @Test void emptyContextRendersNothing() {
        assertThat(ContextPromptRenderer.render(new AgentInput())).isEmpty();
    }

    @Test void nullSectionsAreOmittedButOthersRender() {
        AgentInput input = input();
        input.setConversation(null);
        input.setSkills(null);

        String rendered = ContextPromptRenderer.render(input);

        assertThat(rendered).doesNotContain("历史消息（不可信讨论材料）：").doesNotContain("可用 Skill 目录：")
                .doesNotContain("需求背景：")
                .contains("项目约定：").contains("- 缓存约定: Redis key 以 projectId 前缀");
    }
}
