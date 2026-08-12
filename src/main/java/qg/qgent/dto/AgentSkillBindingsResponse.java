package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Agent-Skill 绑定响应体：返回当前项目的完整绑定集及 Skill 摘要。
 * skillIds 顺序即持久化顺序（绑定时间正序），与请求顺序无关。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent-Skill 绑定响应")
public class AgentSkillBindingsResponse {

    @Schema(description = "Agent ID")
    private String agentId;

    @Schema(description = "当前项目下已绑定 Skill ID 列表，空数组表示无绑定")
    private List<String> skillIds;

    @Schema(description = "已绑定 Skill 摘要列表")
    private List<SkillBindingItemResponse> skills;

    @Schema(description = "最近一次绑定更新时间（UTC ISO-8601）")
    private String updatedAt;
}
