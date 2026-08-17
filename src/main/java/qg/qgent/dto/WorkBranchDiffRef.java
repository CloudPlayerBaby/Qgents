package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 工作分支上最近一个真实 Diff 快照的定位信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "工作分支最近 Diff 快照")
public class WorkBranchDiffRef {
    @Schema(description = "Diff UUID，用于进入 Diff 详情")
    private String id;
    @Schema(description = "产出此 Diff 的 Task UUID")
    private String taskId;
    @Schema(description = "Diff 审查状态")
    private String status;
    @Schema(description = "真实 Diff 统计")
    private Map<String, Object> changeStats;
    @Schema(description = "Diff 创建时间，UTC RFC 3339")
    private String createdAt;
}
