package qg.qgent.orchestration.worker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * Worker 返回的工具执行持久化结果（镜像 Worker 的 ToolExecution）。
 * stdout/stderr 不在此结构内，需通过 {@code /logs} 接口按游标读取。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerToolExecution {

    /** 执行编号。 */
    private UUID id;

    /** 执行所属 Worker 编号。 */
    private String ownerWorkerId;

    /** 所属 Sandbox 编号。 */
    private UUID sandboxId;

    /** 目标仓库编号，无仓库工具为 null。 */
    private UUID repositoryId;

    /** 工具名。 */
    private String tool;

    /** 状态：QUEUED / RUNNING / SUCCEEDED / FAILED / TIMED_OUT / CANCELLED / INTERRUPTED。 */
    private String status;

    /** 退出码，未执行时为 null。 */
    private Integer exitCode;

    /** 工具结构化结果（如 file.read 的 lines、file.write 的 sha256）。 */
    private Map<String, Object> result;

    /** 失败原因，正常结束为 null。 */
    private String failureReason;

    /** 创建时间（ISO-8601 字符串）。 */
    private String createdAt;

    /** 开始时间（ISO-8601 字符串），未开始为 null。 */
    private String startedAt;

    /** 结束时间（ISO-8601 字符串），未结束为 null。 */
    private String finishedAt;
}
