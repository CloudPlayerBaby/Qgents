package qg.qgent.entity;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/** Composite-key relation between a task and an authorized project repository worktree. */
@Data
public class TaskRepositoryEntity {
    /** Owning task identifier. */ private UUID taskId;
    /** Project-repository binding identifier. */ private UUID projectRepositoryId;
    /** Relative mount/worktree name below the task workspace root. */ private String workspacePath;
    /** Base branch or immutable commit requested for the worktree. */ private String baseRef;
    /** UTC relation creation time. */ private LocalDateTime createdAt;
}
