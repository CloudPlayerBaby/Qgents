package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 上下文检索结果（点6：按相关性和标签检索 Skill / Memory / 消息）。
 * <p>
 * 返回轻量 Context DTO（与 {@link GroupContext} 的 skills/memories/conversation 字段一致），
 * 专供 Agent 作为 prompt 输入，不包含审核人/时间等管理元数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContextSearchResponse {

    /**
     * 匹配的已发布 Skill（项目共享或本人）。
     */
    @Schema(description = "匹配的已发布 Skill（项目共享或本人）")
    private List<ContextSkill> skills;

    /**
     * 匹配的已批准 Memory。
     */
    @Schema(description = "匹配的已批准 Memory")
    private List<ContextMemory> memories;

    /**
     * 匹配的消息（仅当关键字非空时查询，可选限定群）。
     */
    @Schema(description = "匹配的消息（仅当关键字非空时查询）")
    private List<ContextMessage> messages;
}
