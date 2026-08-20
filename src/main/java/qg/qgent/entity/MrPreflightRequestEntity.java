package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 分支级 MR 预检申请事实。一个申请可以覆盖同一功能分支上多个已交付 Task。
 */
@Data
@TableName("mr_preflight_requests")
public class MrPreflightRequestEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID projectId;
    private UUID triggerTaskId;
    private UUID projectRepositoryId;
    private UUID workspaceId;
    private String sourceBranch;
    private String targetBranch;
    /** 分支预检上下文的 SHA-256，作为唯一幂等键，避免长分支名撑爆 MySQL 索引。 */
    private String contextHash;
    private String headCommit;
    private String targetCommit;
    private UUID dryRunId;
    private String status;
    private UUID requestedBy;
    private String idempotencyKey;
    private String failureCode;
    private String failureReason;
    private UUID mergeRequestId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
