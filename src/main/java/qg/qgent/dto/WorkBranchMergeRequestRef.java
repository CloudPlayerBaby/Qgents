package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作分支当前 Open MR 的定位信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "工作分支 Open MR")
public class WorkBranchMergeRequestRef {
    @Schema(description = "Merge Request UUID")
    private String id;
    @Schema(description = "GitHub Pull Request 真实编号")
    private Long number;
    @Schema(description = "MR 状态，固定为 OPEN")
    private String status;
}
