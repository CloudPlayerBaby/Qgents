package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MR 详情响应：关联需求群、检查与审查摘要、质量门禁汇总。
 * 完整检查与审查列表见独立的 checks / reviews 接口。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestDetailResponse {
    private String id;
    private String repositoryId;
    private List<String> groupIds;
    private String provider;
    private Long number;
    private String sourceBranch;
    private String targetBranch;
    private String status;
    private String headCommit;
    private String title;
    private String description;
    private String webUrl;
    private String diffId;
    private QualityGateResponse qualityGate;
    private String authorUserId;
    private String syncedAt;
    private String createdAt;
}
