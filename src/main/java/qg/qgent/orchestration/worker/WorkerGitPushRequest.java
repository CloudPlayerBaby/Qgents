package qg.qgent.orchestration.worker;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class WorkerGitPushRequest {
    private String expectedHeadCommit;
    private String credentialGrantId;
}
