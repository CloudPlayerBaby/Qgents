package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 仓库展示摘要（任务列表/详情通用）。
 * <p>
 * repositoryId 恒为 project_repositories.id（项目仓库绑定），不得混用 provider repository ID。
 * baseRef/baseCommit 来自 Workspace worktree 初始化事实，sourceBranch/headCommit 为任务实际工作分支与提交；
 * 未发生的提交 headCommit 为 null。fullName 由 GitHub 仓库镜像的 owner/login + name 拼接，如 qgents/auth-service。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RepositorySummary {

    /** 项目仓库绑定 ID（project_repositories.id）。 */
    @Schema(description = "项目仓库绑定 ID")
    private String repositoryId;

    /** 项目内显示名称；未设置时回落为 GitHub 仓库名。 */
    @Schema(description = "仓库显示名称")
    private String name;

    /** 完整仓库名，如 qgents/auth-service。 */
    @Schema(description = "完整仓库名")
    private String fullName;

    /** 代码托管提供方，固定 GITHUB。 */
    @Schema(description = "代码托管提供方")
    private String provider;

    /** 项目内使用的默认分支（可覆盖 GitHub 默认分支）。 */
    @Schema(description = "项目内默认分支")
    private String defaultBranch;

    /** Workspace 初始化时使用的基准分支。 */
    @Schema(description = "基准分支")
    private String baseRef;

    /** 基准提交 SHA，可为 null。 */
    @Schema(description = "基准提交 SHA")
    private String baseCommit;

    /** 任务实际工作分支。 */
    @Schema(description = "工作分支")
    private String sourceBranch;

    /** 已接受的 Diff 提交 SHA；未提交时为 null。 */
    @Schema(description = "工作分支头提交 SHA")
    private String headCommit;
}
