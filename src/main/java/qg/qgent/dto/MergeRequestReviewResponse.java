package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MR 人工或 AI 审查摘要。reviewKind 枚举：HUMAN/AI。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestReviewResponse {
    private String id;
    private String kind;
    private String reviewerId;
    private String reviewerName;
    private String decision;
    private String summary;
    private String reviewedAt;
}
