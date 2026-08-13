package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知中心视图（A 联调约定 §1）。
 * <p>
 * 返回当前登录用户的持久化通知；kind 枚举：
 * TASK_COMPLETED/TASK_FAILED/AGENT_INPUT_REQUIRED/DELIVERABLE_PENDING/MR_PENDING。
 * SSE 只负责实时提醒，本视图承担历史列表与已读状态。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {

    /** 通知 ID。 */
    @Schema(description = "通知 ID")
    private String id;

    /** 通知类型：TASK_COMPLETED/TASK_FAILED/AGENT_INPUT_REQUIRED/DELIVERABLE_PENDING/MR_PENDING。 */
    @Schema(description = "通知类型：TASK_COMPLETED/TASK_FAILED/AGENT_INPUT_REQUIRED/DELIVERABLE_PENDING/MR_PENDING")
    private String kind;

    /** 一行标题。 */
    @Schema(description = "一行标题")
    private String title;

    /** 补充说明，可为空。 */
    @Schema(description = "补充说明")
    private String description;

    /** 是否已读。 */
    @Schema(description = "是否已读")
    private Boolean isRead;

    /** 产生时间（ISO8601 UTC）。 */
    @Schema(description = "产生时间（ISO8601 UTC）")
    private String createdAt;

    /** 所属项目 ID，点击跳转用；可为空。 */
    @Schema(description = "所属项目 ID")
    private String projectId;

    /** 来源需求群 ID，可为空。 */
    @Schema(description = "来源需求群 ID")
    private String groupId;

    /** 关联资源 ID（taskId/mrId/diffId），跳转定位用；可为空。 */
    @Schema(description = "关联资源 ID（taskId/mrId/diffId）")
    private String resourceId;
}
