package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 任务在 Workspace 下的一个 Repository worktree 的只读范围摘要。
 * 反映任务实际代码操作所基于的分支与提交事实：sourceBranch 是任务共用的特性分支，
 * baseCommit 是创建 worktree 时固定的基线提交，headCommit 是已接受 Diff 后真实产生的
 * 最新提交（未接受前为空）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskRepositoryScopeResponse {
    /**
     * 项目仓库绑定 ID。
     */
    private String repositoryId;
    /**
     * 相对 worktree 名，绝不包含宿主机绝对路径。
     */
    private String workspacePath;
    /**
     * 创建 worktree 时固定的不可变基线提交 SHA；未接受前为空。
     */
    private String baseCommit;
    /**
     * 任务共用特性分支名。
     */
    private String sourceBranch;
    /**
     * 已接受 Diff 后真实产生的头提交 SHA；未接受前为空。
     */
    private String headCommit;
}
