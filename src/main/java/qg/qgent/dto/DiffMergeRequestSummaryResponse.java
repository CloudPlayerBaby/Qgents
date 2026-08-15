package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 总 Diff 响应中的真实 MR 摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DiffMergeRequestSummaryResponse {
    @Schema(description = "MR ID")
    private String id;
    @Schema(description = "GitHub Pull Request 真实编号")
    private Long number;
    @Schema(description = "MR 标题")
    private String title;
    @Schema(description = "MR 状态")
    private String status;
    @Schema(description = "可可靠构造时的 GitHub 页面地址")
    private String webUrl;
}
