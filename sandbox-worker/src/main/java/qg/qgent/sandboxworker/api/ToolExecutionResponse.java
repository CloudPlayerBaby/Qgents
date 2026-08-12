package qg.qgent.sandboxworker.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 工具执行的持久化结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ToolExecutionResponse {
    private UUID id;
    private UUID sandboxId;
    private UUID repositoryId;
    private String tool;
    private String status;
    private Integer exitCode;
    private Map<String, Object> result;
    private String failureReason;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
}
