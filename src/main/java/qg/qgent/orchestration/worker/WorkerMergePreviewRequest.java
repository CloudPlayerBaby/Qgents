package qg.qgent.orchestration.worker;

import lombok.Data;

import java.util.UUID;

/**
 * Worker 只读合并预演请求。
 */
@Data
public class WorkerMergePreviewRequest {
    private UUID repositoryId;
    private String sourceRef;
    private String targetBranch;
}
