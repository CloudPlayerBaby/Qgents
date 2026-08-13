package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Agent-Skill 绑定全量替换请求体。
 * <p>
 * skillIds 为替换后的完整绑定集；空数组表示清空该 Agent 在当前项目的全部绑定。
 * 元素必须为合法 UUID；同一 Skill ID 重复出现视为请求冲突（409 AGENT_SKILL_DUPLICATE）。
 */
@Data
@Schema(description = "Agent-Skill 绑定全量替换请求")
public class AgentSkillBindingsRequest {

    @NotNull(message = "skillIds 不能为 null")
    @Schema(description = "替换后的 Skill ID 列表；空数组清空全部绑定", example = "[\"c0a1...\", \"f3b2...\"]")
    private List<@NotBlank(message = "Skill ID 不能为空白") String> skillIds;
}

