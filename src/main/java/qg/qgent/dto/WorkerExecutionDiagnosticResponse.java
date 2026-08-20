package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关联到单个 TaskRun 的失败 Worker 工具执行脱敏诊断摘要。公开诊断接口至多返回最新一条 status=FAILED 的记录。
 * 完整 Worker 日志仍只可由持有服务令牌的受控运维程序查询。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerExecutionDiagnosticResponse {
    @Schema(description = "Worker 工具执行编号，仅用于受控运维定位完整日志")
    private String executionId;
    @Schema(description = "Worker 工具名称")
    private String tool;
    @Schema(description = "Worker 工具执行状态")
    private String status;
    @Schema(description = "进程类工具退出码，无退出码时为 null")
    private Integer exitCode;
    @Schema(description = "稳定失败码，成功时为 null")
    private String failureCode;
    @Schema(description = "脱敏且限长的失败摘要，成功时为 null")
    private String failureSummary;
    @Schema(description = "创建时间，UTC")
    private String createdAt;
    @Schema(description = "结束时间，UTC；尚未结束时为 null")
    private String finishedAt;
}
