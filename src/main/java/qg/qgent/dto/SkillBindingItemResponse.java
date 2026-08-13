package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 已绑定 Skill 的轻量摘要，供绑定响应直接渲染，无需再逐条查询 Skill 详情。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Agent 已绑定 Skill 摘要")
public class SkillBindingItemResponse {

    @Schema(description = "Skill ID")
    private String id;

    @Schema(description = "Skill 名称")
    private String name;

    @Schema(description = "可见性：PRIVATE/PROJECT_SHARED")
    private String visibility;

    @Schema(description = "状态：DRAFT/PENDING_REVIEW/PUBLISHED/REJECTED/ARCHIVED")
    private String status;
}
