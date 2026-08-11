package qg.qgent.dto;

import lombok.Data;

/**
 * Data transfer object for branch policies.
 */
@Data
public class BranchPolicyDto {
    private Boolean requirePullRequest;
    private Integer minimumHumanApprovals;
    private Boolean allowDirectPush;
}
