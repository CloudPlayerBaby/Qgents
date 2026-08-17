package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 用户可见的需求执行；deliveryMode 持久化区分 Diff-first 与 MR-first 交付路径。
 */
@Data
@TableName("tasks")
public class TaskEntity {
    /**
     * UUIDv7 task identifier.
     */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * Owning project isolation boundary.
     */
    private UUID projectId;
    /**
     * Active REQUIREMENT group providing conversation context.
     */
    private UUID requirementGroupId;
    /**
     * Optional message that triggered this task.
     */
    private UUID triggerMessageId;
    /**
     * Persistent development workspace used by this task.
     */
    private UUID workspaceId;
    /**
     * Previous task continued in the same workspace, when explicitly requested.
     */
    private UUID continuationOfTaskId;
    /**
     * Human-readable task title, at most 255 characters.
     */
    private String title;
    /**
     * 项目内唯一、创建后不可变的展示编号，如 T-1024；不替代 UUID，所有 API 关联仍使用 id。
     */
    private String displayCode;
    /**
     * Immutable requirement text supplied by the requester.
     */
    private String requirement;
    /**
     * Lifecycle state: PLANNING/PENDING/RUNNING/WAITING_DIFF_CONFIRMATION/DELIVERING/SUCCEEDED/DELIVERY_FAILED/FAILED/CANCELLING/CANCELLED.
     */
    private String status;
    /**
     * 代码交付路径：DIFF_FIRST 或 MR_FIRST。
     */
    private String deliveryMode;
    /**
     * 交付模式判定理由（Planner scaleReason 或硬规则依据），供看板/卡片展示；可为空。
     */
    private String deliveryReason;
    /**
     * Planner 计划已原子物化为正式执行步骤的时间；非空后不得由自动编排重写步骤清单。
     */
    private LocalDateTime planMaterializedAt;
    /**
     * Authenticated user who requested the task.
     */
    private UUID createdBy;
    /**
     * UTC creation time.
     */
    private LocalDateTime createdAt;
    /**
     * UTC last-update time.
     */
    private LocalDateTime updatedAt;
}
