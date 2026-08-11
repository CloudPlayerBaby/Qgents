package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 交付物响应，包含关联运行、分支和检查摘要。
 * 状态枚举：PENDING_REVIEW/ACCEPTED/REJECTED。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeliverableResponse {
    private String id;
    private String projectId;
    private String groupId;
    private String workPackageId;
    private String taskRunId;
    private String repositoryId;
    private String sourceBranch;
    private String headCommit;
    private Map<String, Object> summary;
    private String status;
    private String createdBy;
    private String reviewedBy;
    private String reviewReason;
    private String reviewedAt;
    private String createdAt;
}
