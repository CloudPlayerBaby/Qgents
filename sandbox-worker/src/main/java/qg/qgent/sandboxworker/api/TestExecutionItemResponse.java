package qg.qgent.sandboxworker.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * 单个 Testset 的脱敏执行结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestExecutionItemResponse {
    private UUID testsetId;
    private String status;
    private Integer exitCode;
    private long durationMs;
    private String failureCode;
    /**
     * 脱敏且可操作的失败摘要，不返回原始 stdout/stderr。
     */
    private String message;
}
