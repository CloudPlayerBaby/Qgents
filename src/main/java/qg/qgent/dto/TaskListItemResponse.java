package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 任务中心列表项摘要（GET /projects/{projectId}/tasks）。
 * <p>
 * 一次返回任务卡片所需摘要：发起人、需求群、影响仓库、执行统计与待处理事项；
 * 不返回完整日志、Prompt 或文件 Diff。requirementSummary 为服务端截断的纯文本摘要（不含 HTML）。
 * priority 当前明确不提供，字段恒为 null，前端应移除该展示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskListItemResponse {

    /**
     * 任务 ID（UUIDv7，字符串形式）。
     */
    @Schema(description = "任务 ID")
    private String id;

    /**
     * 项目内唯一展示编号，如 T-1024，创建后不可变。
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
     * 需求纯文本摘要（截断，<=200 字符）。
     */
    @Schema(description = "需求纯文本摘要")
    private String requirementSummary;

    /**
     * 任务状态。
     */
    @Schema(description = "任务状态")
    private String status;

    /**
     * 优先级；当前明确不提供，恒为 null。
     */
    @Schema(description = "优先级（当前不提供）")
    private String priority;

    /**
     * 交付模式：DIFF_FIRST/MR_FIRST。
     */
    @Schema(description = "交付模式")
    private String deliveryMode;

    /**
     * 交付模式判定理由（Planner scaleReason 或规则依据）；未判定时为 null。
     */
    @Schema(description = "交付模式判定理由")
    private String deliveryReason;

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
     * 影响仓库摘要列表。
     */
    @Schema(description = "影响仓库摘要")
    private List<RepositorySummary> repositories;

    /**
     * 执行统计摘要。
     */
    @Schema(description = "执行统计摘要")
    private ExecutionSummary executionSummary;

    /**
     * 待处理事项；无则 null。
     */
    @Schema(description = "待处理事项")
    private Attention attention;

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
