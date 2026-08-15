package qg.qgent.orchestration.worker;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Worker 返回的测试运行汇总。
 */
@Data
public class WorkerTestExecutionResponse {
    private UUID executionId;
    private String status;
    private String resolvedHeadCommit;
    private List<WorkerTestExecutionItemResponse> results;
}
