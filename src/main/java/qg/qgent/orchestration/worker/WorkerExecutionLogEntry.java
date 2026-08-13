package qg.qgent.orchestration.worker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一条带递增序号的脱敏执行日志（镜像 Worker 的 ExecutionLogEntry）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerExecutionLogEntry {

    /** 执行内递增序号，用于游标分页。 */
    private long sequence;

    /** 流：SYSTEM / STDOUT / STDERR。 */
    private String stream;

    /** 单条日志内容（已脱敏、截断）。 */
    private String content;

    /** 产生时间（ISO-8601 字符串）。 */
    private String timestamp;
}
