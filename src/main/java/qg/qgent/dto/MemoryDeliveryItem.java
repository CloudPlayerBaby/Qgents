package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MEMORY 交付项：项目 Memory 摘要（契约 v1.8.0 §20）。
 * <p>
 * 只返回脱敏摘要；visibility 当前无独立列（APPROVED 即项目共享），统一返回 PROJECT_SHARED。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class MemoryDeliveryItem extends DeliveryItem {

    /**
     * 知识分类标识，如 ENGINEERING_DECISION。
     */
    @Schema(description = "知识分类标识")
    private String category;

    /**
     * 标签列表；无数据时返回空数组。
     */
    @Schema(description = "标签列表")
    private List<String> tags;

    /**
     * 可见性：当前统一返回 PROJECT_SHARED（APPROVED 即项目共享）。
     */
    @Schema(description = "可见性，当前统一为 PROJECT_SHARED")
    private String visibility;

    /**
     * 来源消息引用列表；无数据时返回空数组。
     */
    @Schema(description = "来源消息引用列表")
    private List<MemorySourceRef> sources;

    /**
     * 内容脱敏摘录（≤200 字符）；无则 null。
     */
    @Schema(description = "内容脱敏摘录")
    private String contentExcerpt;
}
