package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 需求群展示摘要（任务中心/任务详情通用）。
 * <p>
 * 供前端展示"任务属于哪个需求群"，name 为群聊名称，status 为群生命周期状态（ACTIVE/ARCHIVED）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequirementGroupSummary {

    /** 需求群 ID（UUIDv7，字符串形式）。 */
    @Schema(description = "需求群 ID")
    private String id;

    /** 群聊名称。 */
    @Schema(description = "群聊名称")
    private String name;

    /** 群状态：ACTIVE/ARCHIVED。 */
    @Schema(description = "群状态")
    private String status;
}
