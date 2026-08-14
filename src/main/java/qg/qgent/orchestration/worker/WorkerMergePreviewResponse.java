package qg.qgent.orchestration.worker;

import lombok.Data;

import java.util.List;

/** Worker 只读合并预演结果。 */
@Data
public class WorkerMergePreviewResponse {
    private String resolvedHeadCommit;
    private String resolvedTargetCommit;
    private boolean mergeable;
    private List<String> conflicts;
}
