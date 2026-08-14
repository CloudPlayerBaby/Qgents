package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Task 级验收标准展示项。
 * <p>
 * status 枚举：PENDING/SATISFIED/UNSATISFIED/NOT_APPLICABLE；验收状态由后端结果或检查资源决定，
 * 前端只能展示。当前尚无验收标准生产者时返回空数组。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AcceptanceCriterion {

    /** 验收标准 ID（UUIDv7，字符串形式）。 */
    @Schema(description = "验收标准 ID")
    private String id;

    /** 验收标准标题。 */
    @Schema(description = "验收标准标题")
    private String title;

    /** 验收标准补充说明，可为 null。 */
    @Schema(description = "验收标准补充说明")
    private String description;

    /** 验收状态：PENDING/SATISFIED/UNSATISFIED/NOT_APPLICABLE。 */
    @Schema(description = "验收状态")
    private String status;
}
