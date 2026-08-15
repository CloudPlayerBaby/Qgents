package qg.qgent.orchestration.worker;

import lombok.Data;

import java.util.UUID;

/**
 * 主后端发送给 Worker 的单个 Testset 执行定义。
 */
@Data
public class WorkerTestExecutionItemRequest {
    private UUID testsetId;
    private String command;
    private Integer timeoutSeconds;
    private String passRuleType;
    private Integer expectedExitCode;
}
