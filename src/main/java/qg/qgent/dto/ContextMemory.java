package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上下文中的一条 Memory 记录（已批准）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContextMemory {

    /**
     * Memory 标题。
     */
    @Schema(description = "Memory 标题")
    private String title;

    /**
     * 知识正文。
     */
    @Schema(description = "知识正文")
    private String content;

    /**
     * 知识分类标识。
     */
    @Schema(description = "知识分类标识")
    private String category;
}
