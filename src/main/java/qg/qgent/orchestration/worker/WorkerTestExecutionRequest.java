package qg.qgent.orchestration.worker;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/** 主后端发送给 Worker 的受控测试请求。 */
@Data
public class WorkerTestExecutionRequest {
    private UUID executionId;
    private UUID projectId;
    private UUID repositoryId;
    private UUID workspaceId;
    private String ref;
    /** 非空时 Worker 在临时 checkout 中把该源引用合并进 ref 后再执行测试。 */
    private String mergeSourceRef;
    private List<WorkerTestExecutionItemRequest> testsets;
}
