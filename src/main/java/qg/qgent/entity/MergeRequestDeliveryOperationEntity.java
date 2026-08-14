package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/** Worker push 与 GitHub PR 创建之间可恢复、可幂等重试的操作事实。 */
@Data
@TableName("merge_request_delivery_operations")
public class MergeRequestDeliveryOperationEntity {
    @TableId(type = IdType.INPUT) private UUID id;
    private String operationKey;
    private UUID projectId;
    private UUID projectRepositoryId;
    private UUID taskId;
    private UUID workspaceId;
    private UUID actorUserId;
    private String sourceBranch;
    private String targetBranch;
    private String headCommit;
    private String title;
    private String status;
    private String claimToken;
    private LocalDateTime leaseExpiresAt;
    private Long providerNumber;
    private UUID mergeRequestId;
    private String failureCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
