package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 当前可见的仓库清单。名称和工作区别名用于让模型区分多仓库，
 * 分支字段只描述当前 Task 的实际 worktree，不包含凭据或远端访问令牌。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContextRepository {
    @Schema(description = "项目仓库绑定 ID")
    private String repositoryId;
    @Schema(description = "仓库在项目内的显示名称")
    private String name;
    @Schema(description = "GitHub owner/name")
    private String fullName;
    @Schema(description = "项目内默认基线分支")
    private String defaultBranch;
    @Schema(description = "Workspace 内仓库目录别名")
    private String workspacePath;
    @Schema(description = "当前任务固定的基线分支")
    private String baseRef;
    @Schema(description = "当前任务的 feature branch")
    private String sourceBranch;
}
