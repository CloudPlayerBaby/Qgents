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
    /**
     * 已由主后端刷新并冻结的目标提交 SHA。Worker 不得在执行时重新解析目标分支。
     */
    private String targetCommit;
}
