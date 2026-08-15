package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 按用户维度持久化的通知中心实体（A 联调约定 §1）。
 * <p>
 * 由 {@code task.updated}（SUCCEEDED/FAILED）、{@code input-required}/{@code approval-required}、
 * {@code diff.created}、{@code merge-request.updated} 等事件触发写入；SSE 只负责实时提醒，
 * 历史列表与已读状态由本表提供。kind 枚举：TASK_COMPLETED/TASK_FAILED/AGENT_INPUT_REQUIRED/
 * DELIVERABLE_PENDING/MR_PENDING/INVITED/TEAM_JOINED/PROJECT_ADDED。
 * 任务类通知的接收人为任务发起人（Task.createdBy）；INVITED 接收人为被邀请用户，
 * TEAM_JOINED 接收人为邀请者，PROJECT_ADDED 接收人为被加入项目的成员。
 */
@Data
@TableName("notifications")
public class NotificationEntity {

    /**
     * 通知 ID（UUIDv7，BINARY(16)）。
     */
    @TableId(type = IdType.INPUT)
    private UUID id;

    /**
     * 接收通知的用户 ID（BINARY(16)），外键指向 users(id)。
     */
    private UUID recipientUserId;

    /**
     * 关联项目 ID（BINARY(16)），点击跳转用；系统级通知为空。
     */
    private UUID projectId;

    /**
     * 关联需求群 ID（BINARY(16)）；非群来源的通知为空。
     */
    private UUID requirementGroupId;

    /**
     * 通知类型枚举：TASK_COMPLETED/TASK_FAILED/AGENT_INPUT_REQUIRED/DELIVERABLE_PENDING/MR_PENDING/
     * INVITED/TEAM_JOINED/PROJECT_ADDED。
     */
    private String kind;

    /**
     * 一行通知标题（≤255 字符）。
     */
    private String title;

    /**
     * 通知描述正文，补充说明；可为空。
     */
    private String description;

    /**
     * 关联资源 ID 字符串（taskId/mrId/diffId 等），跳转定位用；可为空。
     */
    private String resourceId;

    /**
     * 是否已读：true 已读，false 未读（TINYINT(1)）。
     */
    private Boolean isRead;

    /**
     * 产生时间（UTC）。
     */
    private LocalDateTime createdAt;

    /**
     * 已读时间（UTC），未读为空。
     */
    private LocalDateTime readAt;
}
