package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/** Persistent task-level workspace root; repository worktrees live below this root. */
@Data @TableName("workspaces")
public class WorkspaceEntity {
    /** UUIDv7 workspace identifier. */ @TableId(type = IdType.INPUT) private UUID id;
    /** Task owning this one-to-one workspace. */ private UUID taskId;
    /** Owning project isolation boundary. */ private UUID projectId;
    /** Opaque storage key; never exposes a host path to clients. */ private String storageKey;
    /** Lifecycle state: PROVISIONING/READY/LEASED/ARCHIVED/FAILED. */ private String status;
    /** UTC creation time. */ private LocalDateTime createdAt;
    /** UTC last-update time. */ private LocalDateTime updatedAt;
}
