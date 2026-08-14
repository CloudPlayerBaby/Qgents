package qg.qgent.orchestration.worker;

import lombok.Data;

/** Worker 返回的固定 commit SHA。 */
@Data
public class WorkerGitResolveResponse {
    private String commitSha;
}
