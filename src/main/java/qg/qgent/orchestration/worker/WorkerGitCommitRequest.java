package qg.qgent.orchestration.worker;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Worker request to commit exactly one reviewed worktree snapshot.
 */
@Data
@Accessors(chain = true)
public class WorkerGitCommitRequest {
    private String expectedHeadCommit;
    private String expectedDiffHash;
    private String message;
    private String operationId;
}
