package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

/**
 * Planned workflow node with explicit instructions, role, Agent assignment and acceptance criteria.
 */
@Data
@TableName(value = "task_steps", autoResultMap = true)
public class TaskStepEntity {
    /**
     * UUIDv7 step identifier.
     */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * Task whose workflow contains this step.
     */
    private UUID taskId;
    /**
     * Stable order hint; dependencies remain authoritative.
     */
    private Integer sequenceNo;
    /**
     * Short step title.
     */
    private String title;
    /**
     * Exact work instructions for the assigned Agent.
     */
    private String instruction;
    /**
     * Required execution role such as PLANNER/DEVELOPER/TESTER/REVIEWER.
     */
    private String role;
    /**
     * Replaceable Agent identifier; may be null until scheduling.
     */
    private UUID assignedAgentId;
    /**
     * Acceptance criteria for this step.
     */
    private String acceptanceCriteria;
    /**
     * Planner 为该步骤声明的能力标签；用于固定 Agent 选择依据并供看板展示。
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> requiredCapabilities;
    /**
     * State: PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED/CANCELLED.
     */
    private String status;
    /**
     * UTC creation time.
     */
    private LocalDateTime createdAt;
    /**
     * UTC last-update time.
     */
    private LocalDateTime updatedAt;
}
