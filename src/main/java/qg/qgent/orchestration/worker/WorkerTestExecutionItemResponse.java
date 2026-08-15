package qg.qgent.orchestration.worker;

import lombok.Data;

import java.util.UUID;

/**
 * Worker 返回的单个 Testset 结果。
 */
@Data
public class WorkerTestExecutionItemResponse {
    private UUID testsetId;
    private String status;
    private Integer exitCode;
    private long durationMs;
    private String failureCode;
}
