package qg.qgent.orchestration.worker;

import lombok.Data;

/**
 * Real commit SHA returned by the controlled Worker commit operation.
 */
@Data
public class WorkerGitCommitResponse {
    private String commitSha;
}
