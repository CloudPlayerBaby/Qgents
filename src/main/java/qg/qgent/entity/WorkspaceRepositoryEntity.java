package qg.qgent.entity;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** Persistent repository worktree located below a project-scoped Workspace. */
@Data
public class WorkspaceRepositoryEntity {
    /** Owning Workspace identifier. */
    private UUID workspaceId;
    /** Project repository binding mounted in the Workspace. */
    private UUID projectRepositoryId;
    /** Relative worktree name; never a host absolute path. */
    private String workspacePath;
    /** Immutable commit used when the worktree was created. */
    private String baseCommit;
    /** Feature branch shared by continuation Tasks using this Workspace. */
    private String sourceBranch;
    /**
     * Latest committed feature-branch SHA, or null before the first accepted Diff.
     */
    private String headCommit;
    /** UTC creation time. */
    private LocalDateTime createdAt;
    /** UTC last-update time. */
    private LocalDateTime updatedAt;
}
