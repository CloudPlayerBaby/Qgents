package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MR 列表摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MergeRequestSummaryResponse {
    private String id;
    private String repositoryId;
    private List<String> groupIds;
    private String provider;
    private Long number;
    private String sourceBranch;
    private String targetBranch;
    private String status;
    private String headCommit;
    private QualityGateResponse qualityGate;
    private String title;
    private String createdAt;
}
