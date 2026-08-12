package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 手动创建 Memory 草稿请求（契约 §9）。
 */
@Data
public class MemoryCreateRequest {

    /** 知识标题（必填，≤255）。 */
    @NotBlank
    @Size(max = 255)
    @Schema(description = "知识标题（必填）", maxLength = 255, requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;

    /** 经确认的项目事实正文（必填）。 */
    @NotBlank
    @Schema(description = "经确认的项目事实正文（必填）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    /** 知识分类标识（必填），如 ENGINEERING_DECISION。 */
    @NotBlank
    @Size(max = 64)
    @Schema(description = "知识分类标识，如 ENGINEERING_DECISION", maxLength = 64,
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String category;

    /** 标签列表（可空）。 */
    @Schema(description = "标签列表")
    private List<String> tags;
}
