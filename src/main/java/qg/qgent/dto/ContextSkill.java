package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文中的一条 Skill 记录（已发布、项目共享）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContextSkill {

    /**
     * Skill 名称。
     */
    @Schema(description = "Skill 名称")
    private String name;

    /**
     * 可复用操作规范正文。
     */
    @Schema(description = "可复用操作规范正文")
    private String content;
}
