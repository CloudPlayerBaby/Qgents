package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** Task-level, multi-repository Diff review and delivery state. */
@Data
@TableName("diff_review_batches")
public class DiffReviewBatchEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID projectId;
    private UUID taskId;
    private UUID workspaceId;
    private UUID finalCodingTaskRunId;
    private String reviewStatus;
    private String deliveryStatus;
    private String deliveryOperationId;
    /** 每次领取批次交付时重新生成的 fencing token。 */
    private String deliveryClaimToken;
    private LocalDateTime deliveryLeaseExpiresAt;
    private String aggregateHash;
    private UUID reviewedBy;
    private String reviewReason;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
