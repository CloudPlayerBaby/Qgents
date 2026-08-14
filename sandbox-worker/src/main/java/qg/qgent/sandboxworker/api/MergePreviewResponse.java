package qg.qgent.sandboxworker.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 只读合并预演结果，不包含原始 Git 输出。 */
@Data @NoArgsConstructor @AllArgsConstructor
public class MergePreviewResponse {
    private String resolvedHeadCommit;
    private String resolvedTargetCommit;
    private boolean mergeable;
    private List<String> conflicts;
}
