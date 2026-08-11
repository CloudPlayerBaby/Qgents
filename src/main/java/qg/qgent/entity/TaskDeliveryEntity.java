package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/** Overall review decision for one Task; repository Deliverables are its immutable items. */
@Data
@TableName("task_deliveries")
public class TaskDeliveryEntity {
    /** UUIDv7 delivery identifier. */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** Task being delivered. */
    private UUID taskId;
    /** Monotonic delivery version allocated under a Task row lock. */
    private Integer version;
    /** Owning project isolation boundary. */
    private UUID projectId;
    /** Overall state: PENDING_REVIEW/ACCEPTED/REJECTED. */
    private String status;
    /** Authenticated creator or controlled execution initiator. */
    private UUID createdBy;
    /** Last authenticated reviewer. */
    private UUID reviewedBy;
    /** Review decision explanation. */
    private String reviewReason;
    /** UTC review time. */
    private LocalDateTime reviewedAt;
    /** UTC creation time. */
    private LocalDateTime createdAt;
    /** UTC last-update time. */
    private LocalDateTime updatedAt;
}
