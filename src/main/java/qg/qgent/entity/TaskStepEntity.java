package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

/** Planned workflow node with explicit instructions, role, Agent assignment and acceptance criteria. */
@Data @TableName("task_steps")
public class TaskStepEntity {
    /** UUIDv7 step identifier. */ @TableId(type = IdType.INPUT) private UUID id;
    /** Task whose workflow contains this step. */ private UUID taskId;
    /** Stable order hint; dependencies remain authoritative. */ private Integer sequenceNo;
    /** Short step title. */ private String title;
    /** Exact work instructions for the assigned Agent. */ private String instruction;
    /** Required execution role such as PLANNER/DEVELOPER/TESTER/REVIEWER. */ private String role;
    /** Replaceable Agent identifier; may be null until scheduling. */ private UUID assignedAgentId;
    /** Acceptance criteria for this step. */ private String acceptanceCriteria;
    /** State: PENDING/RUNNING/SUCCEEDED/FAILED/SKIPPED/CANCELLED. */ private String status;
    /** UTC creation time. */ private LocalDateTime createdAt;
    /** UTC last-update time. */ private LocalDateTime updatedAt;
}
