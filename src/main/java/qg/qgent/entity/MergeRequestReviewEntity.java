package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MR 人工或外部 AI 审查记录。
 * reviewKind 枚举：HUMAN/AI；decision 枚举：APPROVED/CHANGES_REQUESTED/COMMENTED。
 * 人工审查必须有 reviewerUserId，AI 审查必须有 reviewerName（由外部同步摘要产生）。
 */
@Data
@TableName("merge_request_reviews")
public class MergeRequestReviewEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * 所属 MR ID。
     */
    private UUID mergeRequestId;
    /**
     * 审查主体枚举：HUMAN/AI。
     */
    private String reviewKind;
    /**
     * 人工审查用户ID；AI 审查时为空。
     */
    private UUID reviewerUserId;
    /**
     * 外部 AI 或人工审查主体展示名；人工审查时为空。
     */
    private String reviewerName;
    /**
     * GitHub Review 外部ID，可为空。
     */
    private String providerReviewId;
    /**
     * 审查结论，取值见类注释。
     */
    private String decision;
    /**
     * 审查意见摘要。
     */
    private String summary;
    /**
     * 审查发生时间（UTC）。
     */
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
