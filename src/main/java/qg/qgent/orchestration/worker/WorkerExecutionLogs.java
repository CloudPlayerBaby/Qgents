package qg.qgent.orchestration.worker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 工具执行日志的游标查询结果（镜像 Worker 的 ExecutionLogs）。
 * nextCursor 用于下一次 {@code after} 参数，等于 0 或上次游标表示已读尽。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerExecutionLogs {

    /** 有序增量日志。 */
    private List<WorkerExecutionLogEntry> items;

    /** 下一次查询的起始游标。 */
    private long nextCursor;
}
