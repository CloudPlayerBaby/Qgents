package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/** A user-visible requirement execution created from an active requirement group. */
@Data @TableName("tasks")
public class TaskEntity {
    /** UUIDv7 task identifier. */ @TableId(type = IdType.INPUT) private UUID id;
    /** Owning project isolation boundary. */ private UUID projectId;
    /** Active REQUIREMENT group providing conversation context. */ private UUID requirementGroupId;
    /** Optional message that triggered this task. */ private UUID triggerMessageId;
    /** Human-readable task title, at most 255 characters. */ private String title;
    /** Immutable requirement text supplied by the requester. */ private String requirement;
    /** Lifecycle state: PLANNING/PENDING/RUNNING/SUCCEEDED/FAILED/CANCELLED. */ private String status;
    /** Authenticated user who requested the task. */ private UUID createdBy;
    /** UTC creation time. */ private LocalDateTime createdAt;
    /** UTC last-update time. */ private LocalDateTime updatedAt;
}
