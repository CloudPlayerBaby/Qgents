package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 以 Task 为入口的统一失败诊断。
 *
 * <p>调用方只需要 taskId。若失败发生在创建 TaskRun 前，latestFailedRun 为 null，
 * 但 failure 仍包含 Task 级启动/编排原因；若已经执行过步骤，则同时返回最近失败
 * TaskRun 的阶段、Worker 执行摘要和 executionId。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskDiagnosticsResponse {
    @Schema(description = "Task ID")
    private String taskId;
    @Schema(description = "Task 当前状态")
    private String status;
    @Schema(description = "失败所在阶段：PLANNING/CODING/TESTING/REVIEWING/DELIVERY")
    private String stage;
    @Schema(description = "Task 级脱敏失败原因；无失败时为 null")
    private TaskStatusReason failure;
    @Schema(description = "最近一次失败 TaskRun 诊断；失败发生在 TaskRun 创建前时为 null")
    private TaskRunDiagnosticsResponse latestFailedRun;
}
