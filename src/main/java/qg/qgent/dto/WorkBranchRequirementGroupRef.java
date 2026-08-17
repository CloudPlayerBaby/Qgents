package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 与工作分支关联任务相关的需求群摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "工作分支相关需求群")
public class WorkBranchRequirementGroupRef {
    @Schema(description = "Requirement Group UUID")
    private String id;
    @Schema(description = "需求群名称")
    private String title;
}
