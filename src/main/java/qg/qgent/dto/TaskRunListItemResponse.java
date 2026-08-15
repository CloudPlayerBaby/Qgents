package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 任务运行列表项摘要（GET /projects/{projectId}/tasks/{taskId}/task-runs）。
 * <p>
 * 供右侧执行记录 Tab 展示：所属步骤标题、执行 Agent、状态、脱敏状态摘要、等待/失败原因与时间。
 * statusSummary 为脱敏摘要，不返回日志原文、Prompt、Token 或环境变量；详情仍需 TaskRun 详情接口。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskRunListItemResponse {

    /**
     * 任务运行 ID（UUIDv7，字符串形式）。
     */
    @Schema(description = "任务运行 ID")
    private String id;

    /**
     * 所属任务 ID。
     */
    @Schema(description = "所属任务 ID")
    private String taskId;

    /**
     * 所属任务步骤 ID。
     */
    @Schema(description = "所属任务步骤 ID")
    private String taskStepId;

    /**
     * 所属任务步骤标题。
     */
    @Schema(description = "所属任务步骤标题")
    private String taskStepTitle;

    /**
     * 执行角色。
     */
    @Schema(description = "执行角色")
    private String role;

    /**
     * 执行 Agent 摘要；未分配时为 null。
     */
    @Schema(description = "执行 Agent 摘要")
    private AgentSummary agent;

    /**
     * 运行状态。
     */
    @Schema(description = "运行状态")
    private String status;

    /**
     * 脱敏状态摘要，说明当前等待/失败原因。
     */
    @Schema(description = "脱敏状态摘要")
    private String statusSummary;

    /**
     * 等待/阻塞/失败原因详情；无时 null。
     */
    @Schema(description = "等待/失败原因")
    private TaskStatusReason statusReason;

    /**
     * 重试来源运行 ID；首次运行为 null。
     */
    @Schema(description = "重试来源运行 ID")
    private String retryOfTaskRunId;

    /**
     * 开始时间（UTC），可为 null。
     */
    @Schema(description = "开始时间")
    private String startedAt;

    /**
     * 结束时间（UTC），可为 null。
     */
    @Schema(description = "结束时间")
    private String finishedAt;

    /**
     * 执行耗时毫秒数，可为 null。
     */
    @Schema(description = "执行耗时毫秒")
    private Long durationMs;

    /**
     * 运行产物摘要（如 diffCount），可为空 Map。
     */
    @Schema(description = "运行产物摘要")
    private Map<String, Object> artifactSummary;

    /**
     * 创建时间（UTC）。
     */
    @Schema(description = "创建时间")
    private String createdAt;

    /**
     * 更新时间（UTC）。
     */
    @Schema(description = "更新时间")
    private String updatedAt;
}
