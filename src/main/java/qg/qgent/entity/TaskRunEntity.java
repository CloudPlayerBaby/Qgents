package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One controlled execution attempt of a planned {@code TaskStep}.
 */
@Data
@TableName("task_runs")
public class TaskRunEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * 所属项目ID，用于项目隔离与鉴权。
     */
    private UUID projectId;
    /**
     * Confirmed top-level task owning this immutable execution attempt.
     */
    private UUID taskId;
    /**
     * Planned task step executed by this attempt.
     */
    private UUID taskStepId;
    /**
     * Agent selected when this attempt was created; retained when later steps change assignment.
     */
    private UUID agentId;
    /**
     * 执行角色枚举：ORCHESTRATOR/PLANNER/DEVELOPER/TESTER/REVIEWER/GENERAL。
     */
    private String role;
    /**
     * 运行状态，取值见类注释。
     */
    private String status;
    /**
     * 重试来源的任务运行ID，为空表示首次运行。
     */
    private UUID retryOfTaskRunId;
    /**
     * 开始执行时间（UTC）。
     */
    private LocalDateTime startedAt;
    /**
     * 结束时间（UTC）。
     */
    private LocalDateTime finishedAt;
    /**
     * 发起用户ID。
     */
    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
