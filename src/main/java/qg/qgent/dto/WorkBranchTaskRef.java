package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作分支最近关联任务的只读摘要，不表示分支的唯一所有者。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "工作分支最近关联任务")
public class WorkBranchTaskRef {
    @Schema(description = "Task UUID")
    private String id;
    @Schema(description = "项目内展示编号，例如 T-1024")
    private String displayCode;
    @Schema(description = "任务标题")
    private String title;
    @Schema(description = "Task 最近更新时间，UTC RFC 3339")
    private String updatedAt;
}
