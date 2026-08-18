package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 上下文中的一条可激活 Skill 目录记录。
 * <p>
 * 默认上下文只携带标识与名称；完整正文必须由运行时工具显式激活，避免未使用的 Skill
 * 占用模型上下文。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContextSkill {

    /**
     * Skill ID，用于调用 {@code activate_skill}。
     */
    @Schema(description = "Skill ID，可用于 activate_skill")
    private UUID id;

    /**
     * Skill 名称。
     */
    @Schema(description = "Skill 名称")
    private String name;
}
