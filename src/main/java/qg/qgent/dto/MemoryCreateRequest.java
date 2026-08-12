package qg.qgent.dto;

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
    private String title;

    /** 经确认的项目事实正文（必填）。 */
    @NotBlank
    private String content;

    /** 知识分类标识（必填），如 ENGINEERING_DECISION。 */
    @NotBlank
    @Size(max = 64)
    private String category;

    /** 标签列表（可空）。 */
    private List<String> tags;
}
