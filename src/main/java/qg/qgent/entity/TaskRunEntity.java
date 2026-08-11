package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 子任务受控执行记录（TaskRun）。
 * 将编排产生的子任务实际执行持久化；它不是平行顶层任务，必须锚定既有编排运行、
 * 工作包与子任务，并继承其项目、需求群与仓库归属。
 * 状态枚举：QUEUED/RUNNING/SUCCEEDED/FAILED/WAITING_INPUT/WAITING_APPROVAL/BLOCKED/CANCELLING/CANCELLED。
 */
@Data
@TableName("task_runs")
public class TaskRunEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属项目ID，用于项目隔离与鉴权。 */
    private UUID projectId;
    /** Confirmed top-level task owning this immutable execution attempt. */
    private UUID taskId;
    /** Planned task step executed by this attempt. */
    private UUID taskStepId;
    /** Agent selected when this attempt was created; retained when later steps change assignment. */
    private UUID agentId;
    /** 所属编排运行ID；第11节建表后补外键，当前仅作归属锚定。 */
    /** @deprecated Legacy read-only compatibility anchor; new writes use taskId. */
    @Deprecated
    private UUID orchestrationRunId;
    /** 所属工作包ID；第11节建表后补外键，当前仅作归属锚定。 */
    /** @deprecated Legacy read-only compatibility anchor; new writes use taskId. */
    @Deprecated
    private UUID workPackageId;
    /** 关联子任务ID；第11节建表后补外键，当前仅作归属锚定。 */
    /** @deprecated Legacy read-only compatibility anchor; new writes use taskStepId. */
    @Deprecated
    private UUID subTaskId;
    /** 项目仓库绑定ID，继承自工作包的仓库。 */
    private UUID projectRepositoryId;
    /** 关联需求群ID，可为空。 */
    private UUID requirementGroupId;
    /** 执行角色枚举：ORCHESTRATOR/PLANNER/DEVELOPER/TESTER/REVIEWER/GENERAL。 */
    private String role;
    /** 运行状态，取值见类注释。 */
    private String status;
    /** 重试来源的任务运行ID，为空表示首次运行。 */
    private UUID retryOfTaskRunId;
    /** 关联的持久 Workspace 标识，由受控执行服务填充。 */
    private String workspaceId;
    /** 沙箱状态摘要，由受控执行服务填充。 */
    private String sandboxStatus;
    /** 基线分支或基线提交。 */
    private String baseRef;
    /** 执行使用的工作分支或提交。 */
    private String headRef;
    /** 开始执行时间（UTC）。 */
    private LocalDateTime startedAt;
    /** Workspace/Sandbox 会话过期时间（UTC）。 */
    private LocalDateTime expiresAt;
    /** 结束时间（UTC）。 */
    private LocalDateTime finishedAt;
    /** 发起用户ID。 */
    private UUID createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
