package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * TaskRun 统一失败诊断入口。
 *
 * <p>每个失败运行都返回主后端阶段和脱敏失败原因；只有实际调用过 Sandbox Worker 的运行才会
 * 包含 {@code workerExecutions}。空数组表示本次失败发生在调用 Worker 之前或未启用 Worker，
 * 不是诊断查询失败。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskRunDiagnosticsResponse {
    @Schema(description = "TaskRun ID")
    private String taskRunId;
    @Schema(description = "Task ID")
    private String taskId;
    @Schema(description = "运行状态")
    private String status;
    @Schema(description = "失败所在执行阶段，由 TaskStep 角色映射")
    private String stage;
    @Schema(description = "主后端失败摘要；非失败运行可为 null")
    private TaskStatusReason failure;
    @Schema(description = "关联的 Worker 工具执行脱敏摘要；未调用 Worker 时为空数组")
    private List<WorkerExecutionDiagnosticResponse> workerExecutions;
}
