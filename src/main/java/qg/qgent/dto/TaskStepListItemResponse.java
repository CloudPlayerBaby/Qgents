package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务步骤展示项（GET /projects/{projectId}/tasks/{taskId}/steps）。
 * <p>
 * 供执行流程卡片展示：步骤序号、标题、角色、执行 Agent、目标仓库、依赖、状态、验收说明与最新运行。
 * description 为步骤工作说明的脱敏展示；acceptanceNotes 为步骤验收条件。
 * 结构化 checkpoints 当前执行器未持久化，后端明确不提供（前端仅展示 acceptanceNotes，不伪造 checklist）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskStepListItemResponse {

    /** 步骤 ID（UUIDv7，字符串形式）。 */
    @Schema(description = "步骤 ID")
    private String id;

    /** 所属任务 ID。 */
    @Schema(description = "所属任务 ID")
    private String taskId;

    /** 任务内步骤序号，从 1 开始。 */
    @Schema(description = "步骤序号")
    private Integer sequenceNo;

    /** 步骤标题。 */
    @Schema(description = "步骤标题")
    private String title;

    /** 步骤工作说明（脱敏展示）。 */
    @Schema(description = "步骤工作说明")
    private String description;

    /** 所需执行角色。 */
    @Schema(description = "所需执行角色")
    private String role;

    /** 分配的 Agent 摘要；未分配时为 null。 */
    @Schema(description = "分配 Agent 摘要")
    private AgentSummary agent;

    /** 步骤目标仓库摘要；未关联时为 null。 */
    @Schema(description = "步骤目标仓库摘要")
    private RepositorySummary repository;

    /** 依赖的步骤 ID 列表。 */
    @Schema(description = "依赖步骤 ID")
    private List<String> dependencies;

    /** 步骤状态：PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED/CANCELLED。 */
    @Schema(description = "步骤状态")
    private String status;

    /** 步骤验收说明，可为 null。 */
    @Schema(description = "步骤验收说明")
    private String acceptanceNotes;

    /** 最新一次执行尝试摘要；尚无运行时为 null。 */
    @Schema(description = "最新执行尝试摘要")
    private TaskStepLatestRun latestRun;

    /** 该步骤累计执行次数（含重试）。 */
    @Schema(description = "累计执行次数")
    private int runCount;

    /** 步骤开始时间（UTC），可为 null。 */
    @Schema(description = "步骤开始时间")
    private String startedAt;

    /** 步骤结束时间（UTC），可为 null。 */
    @Schema(description = "步骤结束时间")
    private String finishedAt;

    /** 创建时间（UTC）。 */
    @Schema(description = "创建时间")
    private String createdAt;

    /** 更新时间（UTC）。 */
    @Schema(description = "更新时间")
    private String updatedAt;
}
