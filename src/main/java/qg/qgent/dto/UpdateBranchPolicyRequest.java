package qg.qgent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request payload for updating branch policies.
 */
@Data
public class UpdateBranchPolicyRequest {
    @NotNull
    private Boolean requirePullRequest;
    
    @NotNull
    private Integer minimumHumanApprovals;
    
    @NotNull
    private Boolean allowDirectPush;
}
