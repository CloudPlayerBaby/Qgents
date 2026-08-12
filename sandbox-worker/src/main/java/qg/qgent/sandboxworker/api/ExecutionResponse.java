package qg.qgent.sandboxworker.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** 异步执行的当前状态。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionResponse {
    private UUID id;
    private UUID sandboxId;
    private String status;
    private Integer exitCode;
    private String failureReason;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
}
