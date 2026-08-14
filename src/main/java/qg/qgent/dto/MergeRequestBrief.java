package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 仓库交付成功后关联的 MR 展示摘要（repositoryDeliveries 用）。
 * <p>
 * number 为 Git 提供方真实编号；status 为 MR 状态（OPEN/MERGED/CLOSED）；
 * webUrl 指向 MR 外部入口，无法可靠构造时为 null。MR 完整审查与合并由 MR 模块负责，此处仅展示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestBrief {

    /** MR 镜像 ID（UUIDv7，字符串形式）。 */
    @Schema(description = "MR ID")
    private String id;

    /** Git 提供方真实编号。 */
    @Schema(description = "MR 编号")
    private Long number;

    /** MR 标题。 */
    @Schema(description = "MR 标题")
    private String title;

    /** MR 状态：OPEN/MERGED/CLOSED。 */
    @Schema(description = "MR 状态")
    private String status;

    /** MR 外部链接，可为 null。 */
    @Schema(description = "MR 外部链接")
    private String webUrl;
}
