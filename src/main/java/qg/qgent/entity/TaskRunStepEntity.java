package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 任务运行内的工作流节点状态（步骤）。
 * 一个 TaskRun 可能经历多个节点（如 DEVELOPER/TESTER/REVIEWER）。
 * 节点状态枚举：PENDING/RUNNING/PASSED/FAILED/SKIPPED/CANCELLED。
 */
@Data
@TableName("task_run_steps")
public class TaskRunStepEntity {
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** 所属任务运行ID。 */
    private UUID taskRunId;
    /** 工作流节点名，如 DEVELOPER/TESTER/REVIEWER。 */
    private String node;
    /** 节点状态，取值见类注释。 */
    private String status;
    /** 节点开始时间（UTC）。 */
    private LocalDateTime startedAt;
    /** 节点结束时间（UTC）。 */
    private LocalDateTime finishedAt;
    /** 节点耗时，单位毫秒。 */
    private Long durationMs;
    /** 节点失败时的错误码，可为空。 */
    private String errorCode;
    private LocalDateTime createdAt;
}
