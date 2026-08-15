package qg.qgent.sandboxworker.api;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * 一条带递增序号的脱敏执行日志。
 */
@Data
@AllArgsConstructor
public class ExecutionLogEntryResponse {
    private long sequence;
    private String stream;
    private String content;
    private Instant timestamp;
}
