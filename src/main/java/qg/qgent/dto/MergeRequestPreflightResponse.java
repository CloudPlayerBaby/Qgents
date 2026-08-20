package qg.qgent.dto;

import lombok.Data;

import java.util.List;

/** 分支级 MR 预检申请及其当前状态。 */
@Data
public class MergeRequestPreflightResponse {
    private String id;
    private String taskId;
    private String repositoryId;
    private String sourceBranch;
    private String targetBranch;
    private String headCommit;
    private String targetCommit;
    private String status;
    private String dryRunId;
    private List<String> coveredTaskIds;
    private List<String> coveredDiffIds;
    private String failureCode;
    private String failureReason;
    private String branchLockStatus;
    private MergeRequestSummaryResponse mergeRequest;
}
