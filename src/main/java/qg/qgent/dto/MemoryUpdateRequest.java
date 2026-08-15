package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 编辑 Memory 草稿/审核中内容请求（契约 §9，PATCH 语义：null 字段表示不修改）。
 */
@Data
public class MemoryUpdateRequest {

    /**
     * 新标题（≤255，null 表示不修改）。
     */
    @Size(max = 255)
    @Schema(description = "新标题，null 表示不修改", maxLength = 255)
    private String title;

    /**
     * 新正文（null 表示不修改）。
     */
    @Schema(description = "新正文，null 表示不修改")
    private String content;

    /**
     * 新分类（null 表示不修改）。
     */
    @Size(max = 64)
    @Schema(description = "新分类，null 表示不修改", maxLength = 64)
    private String category;

    /**
     * 新标签列表（null 表示不修改）。
     */
    @Schema(description = "新标签列表，null 表示不修改")
    private List<String> tags;
}
