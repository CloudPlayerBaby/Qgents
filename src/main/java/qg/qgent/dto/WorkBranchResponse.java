package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Qgents 已知的工作分支只读视图；不是 GitHub 全量远端分支清单。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "项目工作分支视图")
public class WorkBranchResponse {
    @Schema(description = "项目仓库绑定 UUID，即 project_repositories.id")
    private String projectRepositoryId;
    @Schema(description = "Git 工作分支名；与 projectRepositoryId 共同构成行逻辑唯一键")
    private String name;
    @Schema(description = "最近活动关联的 Workspace UUID")
    private String workspaceId;
    @Schema(description = "该分支最近已知的真实提交 SHA；未提交时为空")
    private String lastKnownHead;
    @Schema(description = "最近关联 Task；不表示唯一所有者")
    private WorkBranchTaskRef latestTask;
    @Schema(description = "关联 Task 所在的需求群集合")
    private List<WorkBranchRequirementGroupRef> requirementGroups;
    @Schema(description = "该分支最近的真实 Diff 快照；不存在时为空")
    private WorkBranchDiffRef latestDiff;
    @Schema(description = "该分支唯一的 Open MR；不存在时为空")
    private WorkBranchMergeRequestRef openMergeRequest;
    @Schema(description = "仅在测试确实针对 lastKnownHead 时返回")
    private WorkBranchVerificationRef lastVerification;
}
