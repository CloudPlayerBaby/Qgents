package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Task-level, multi-repository Diff review and delivery state.
 */
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
    /**
     * 交付授权来源：USER=用户确认（DIFF_FIRST）；SYSTEM=系统按 MR_FIRST 规则自动授权。
     * reviewStatus=ACCEPTED 统一表示「已获准进入交付」，谁授权由本字段表达；
     * SYSTEM 只能由 MR_FIRST 内部流程写入，客户端 confirm 接口固定写 USER。
     */
    private String confirmationSource;
    private String deliveryStatus;
    private String deliveryOperationId;
    /**
     * 每次领取批次交付时重新生成的 fencing token。
     */
    private String deliveryClaimToken;
    private LocalDateTime deliveryLeaseExpiresAt;
    private String aggregateHash;
    private UUID reviewedBy;
    private String reviewReason;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
