package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 任务产物数量统计摘要（任务详情右侧 Tab 使用）。
 * <p>
 * byType 按键为产物类型（PLAN/CODING/TESTING/REVIEWING），值为该类产物数量；无产物时返回空 Map，total 为 0。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactSummary {

    /** 产物总数。 */
    @Schema(description = "产物总数")
    private int total;

    /** 按产物类型分类的数量统计。 */
    @Schema(description = "按类型统计数量")
    private Map<String, Integer> byType;
}
