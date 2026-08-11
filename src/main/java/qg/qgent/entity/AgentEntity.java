package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.util.UUID;

/** Team-scoped Agent identity used for safe workflow assignment. */
@Data
@TableName("agents")
public class AgentEntity {
    /** UUIDv7 Agent identifier. */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** Team that owns the Agent. */
    private UUID teamId;
    /** Optional creating user for PRIVATE visibility checks. */
    private UUID createdBy;
    /** Display name shown in workflow configuration. */
    private String name;
    /** Declared role matched against TaskStep.role. */
    private String role;
    /** Visibility: TEAM or PRIVATE. */
    private String visibility;
    /** Lifecycle state; only ACTIVE Agents are assignable. */
    private String status;
}
