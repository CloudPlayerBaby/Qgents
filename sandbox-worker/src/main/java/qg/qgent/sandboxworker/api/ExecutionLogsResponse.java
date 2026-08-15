package qg.qgent.sandboxworker.api;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 执行日志的游标查询结果。
 */
@Data
@AllArgsConstructor
public class ExecutionLogsResponse {
    private List<ExecutionLogEntryResponse> items;
    private long nextCursor;
}
