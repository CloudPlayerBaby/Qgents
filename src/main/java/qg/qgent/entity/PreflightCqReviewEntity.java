package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MR 创建前的人工 CQ 审查事实。
 *
 * 记录始终绑定一条已经结束的 Dry Run 及其固定的源/目标提交；新提交或目标基准变化后，
 * 旧记录不会被复用为新的预检通过结果。
 */
@Data
@TableName("preflight_cq_reviews")
public class PreflightCqReviewEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    private UUID projectId;
    private UUID taskId;
    private UUID projectRepositoryId;
    private UUID dryRunId;
    private String sourceCommit;
    private String targetBranch;
    private String targetCommit;
    /** APPROVED / REJECTED。 */
    private String decision;
    private UUID reviewerUserId;
    private String reason;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
