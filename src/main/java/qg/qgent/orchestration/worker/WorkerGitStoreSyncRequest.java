package qg.qgent.orchestration.worker;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class WorkerGitStoreSyncRequest {
    private String repositoryUrl;
    private String remoteBranch;
    private String expectedHeadCommit;
    private String credentialGrantId;
}
