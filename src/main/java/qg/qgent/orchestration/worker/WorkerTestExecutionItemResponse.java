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
    /**
     * 由 Worker 生成的脱敏、稳定失败说明；不包含命令原始输出或宿主机路径。
     */
    private String message;
}
