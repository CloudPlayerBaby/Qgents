package qg.qgent.orchestration.worker;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class WorkerGitPushResponse {
    private String branch;
    private String headCommit;
    private boolean verified;
}
