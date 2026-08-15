package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SKILL 交付项：项目 Skill 摘要（契约 v1.8.0 §20）。
 * <p>
 * 只返回脱敏摘要；capabilitySummary 当前无数据源，恒为 null。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class SkillDeliveryItem extends DeliveryItem {

    /**
     * 标签列表；无数据时返回空数组。
     */
    @Schema(description = "标签列表")
    private List<String> tags;

    /**
     * 可见性：PRIVATE / PROJECT_SHARED。
     */
    @Schema(description = "可见性")
    private String visibility;

    /**
     * 能力摘要；当前无数据源，恒为 null。
     */
    @Schema(description = "能力摘要，当前恒为 null")
    private String capabilitySummary;

    /**
     * 内容脱敏摘录（≤200 字符）；无则 null。
     */
    @Schema(description = "内容脱敏摘录")
    private String contentExcerpt;
}
