package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务详情摘要（GET /projects/{projectId}/tasks/{taskId}）。
 * <p>
 * 供任务详情页与右侧 Tab 预览：包含完整 requirement、验收标准、Workspace、操作能力、
 * 产物统计、总 Diff 摘要与来源消息。acceptanceCriteria 当前无生产者时返回空数组；
 * capabilities 由后端按当前调用者派生；priority 当前不提供恒为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskDetailResponse {

    /**
     * 任务 ID（UUIDv7，字符串形式）。
     */
    @Schema(description = "任务 ID")
    private String id;

    /**
     * 项目内唯一展示编号。
     */
    @Schema(description = "项目内唯一展示编号")
    private String displayCode;

    /**
     * 所属项目 ID。
     */
    @Schema(description = "所属项目 ID")
    private String projectId;

    /**
     * 任务标题。
     */
    @Schema(description = "任务标题")
    private String title;

    /**
     * 完整需求文本。
     */
    @Schema(description = "完整需求文本")
    private String requirement;

    /**
     * 任务状态。
     */
    @Schema(description = "任务状态")
    private String status;

    /**
     * 优先级；当前不提供，恒为 null。
     */
    @Schema(description = "优先级（当前不提供）")
    private String priority;

    /**
     * 交付模式：DIFF_FIRST/MR_FIRST。
     */
    @Schema(description = "交付模式")
    private String deliveryMode;

    /**
     * 需求群摘要。
     */
    @Schema(description = "需求群摘要")
    private RequirementGroupSummary requirementGroup;

    /**
     * 发起人摘要。
     */
    @Schema(description = "发起人摘要")
    private UserSummary createdByUser;

    /**
     * Task 级验收标准列表；无生产者时为空数组。
     */
    @Schema(description = "Task 级验收标准")
    private List<AcceptanceCriterion> acceptanceCriteria;

    /**
     * 执行统计摘要。
     */
    @Schema(description = "执行统计摘要")
    private ExecutionSummary executionSummary;

    /**
     * 关联 Workspace 摘要。
     */
    @Schema(description = "关联 Workspace 摘要")
    private WorkspaceSummary workspace;

    /**
     * 当前用户操作能力。
     */
    @Schema(description = "当前用户操作能力")
    private TaskCapabilities capabilities;

    /**
     * 产物数量统计。
     */
    @Schema(description = "产物数量统计")
    private ArtifactSummary artifactSummary;

    /**
     * 总 Diff 审核与交付摘要。
     */
    @Schema(description = "总 Diff 摘要")
    private DiffReviewSummary diffReviewSummary;

    /**
     * 来源消息摘要；无触发消息时为 null。
     */
    @Schema(description = "来源消息摘要")
    private SourceMessage sourceMessage;

    /**
     * 触发消息 ID；无触发消息时为 null。
     */
    @Schema(description = "触发消息 ID")
    private String triggerMessageId;

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
