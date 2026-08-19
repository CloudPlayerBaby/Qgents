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
    /** Dry Run 合并测试实际应用的源提交；非合并测试为空。 */
    private String resolvedSourceCommit;
    /** Dry Run 合并测试使用的固定目标提交；非合并测试为空。 */
    private String resolvedTargetCommit;
    private List<WorkerTestExecutionItemResponse> results;
}
