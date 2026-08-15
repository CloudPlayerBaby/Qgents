package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MR 摘要（契约 v1.8.0 §20）。
 * <p>
 * number 为真实 Git 提供方编号；webUrl 由仓库镜像与真实 PR 编号构造，不可靠构造时为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestSummary {

    /**
     * MR 记录 ID。
     */
    @Schema(description = "MR 记录 ID")
    private String id;

    /**
     * 提供方编号（GitHub PR number）。
     */
    @Schema(description = "提供方编号")
    private Long number;

    /**
     * MR 标题。
     */
    @Schema(description = "MR 标题")
    private String title;

    /**
     * MR 状态：OPEN / MERGED / CLOSED。
     */
    @Schema(description = "MR 状态")
    private String status;

    /**
     * 提供方 Web 地址；不可构造时 null。
     */
    @Schema(description = "提供方 Web 地址")
    private String webUrl;
}
