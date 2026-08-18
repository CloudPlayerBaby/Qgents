package qg.qgent.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persistent repository worktree located below a project-scoped Workspace.
 */
@Data
public class WorkspaceRepositoryEntity {
    /**
     * Owning Workspace identifier.
     */
    private UUID workspaceId;
    /**
     * Project repository binding mounted in the Workspace.
     */
    private UUID projectRepositoryId;
    /**
     * Relative worktree name; never a host absolute path.
     */
    private String workspacePath;
    /**
     * Immutable commit used when the worktree was created. Null until Worker
     * provision resolves and reports the real SHA; never a branch name afterwards.
     */
    private String baseCommit;
    /**
     * 不可变基线分支名，创建 worktree 时固定；sync 与 provision 必须使用同一值。
     * 兼容迁移前旧数据：为空时回退用 base_commit 中的分支名（非 SHA 形态）。
     */
    private String baseRef;
    /**
     * Feature branch shared by continuation Tasks using this Workspace.
     */
    private String sourceBranch;
    /**
     * Latest committed feature-branch SHA, or null before the first accepted Diff.
     */
    private String headCommit;
    /**
     * UTC creation time.
     */
    private LocalDateTime createdAt;
    /**
     * UTC last-update time.
     */
    private LocalDateTime updatedAt;
}
