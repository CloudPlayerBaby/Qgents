package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 创建 Skill 草稿请求（契约 §8）。
 */
@Data
public class SkillCreateRequest {

    /** Skill 名称（必填，≤255）。 */
    @NotBlank
    @Size(max = 255)
    private String name;

    /** 可复用操作规范正文（必填）。 */
    @NotBlank
    private String content;

    /** 标签列表（可空）。 */
    private List<String> tags;

    /** 可见性：PRIVATE / PROJECT_SHARED；默认 PRIVATE。 */
    @Size(max = 32)
    private String visibility;
}
