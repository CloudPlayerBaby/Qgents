package qg.qgent.entity;

import lombok.Data;

/**
 * JSON structure for branch policy configuration stored in repository_branch_configs.policy_json.
 */
@Data
public class BranchPolicyJson {
    /**
     * Whether pull requests are required.
     */
    private Boolean requirePullRequest;
    
    /**
     * Minimum number of human approvals required.
     */
    private Integer minimumHumanApprovals;
    
    /**
     * Whether direct push is allowed.
     */
    private Boolean allowDirectPush;
}
