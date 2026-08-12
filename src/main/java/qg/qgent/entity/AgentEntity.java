package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/** Team-scoped Agent identity used for safe workflow assignment. */
@Data
@TableName(value = "agents", autoResultMap = true)
public class AgentEntity {
    /** UUIDv7 Agent identifier. */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /** Team that owns the Agent. */
    private UUID teamId;
    /** Optional creating user for PRIVATE visibility checks. */
    private UUID createdBy;
    /** Display name shown in workflow configuration (昵称). */
    private String name;
    /** Declared role matched against TaskStep.role (角色标签). */
    private String role;
    /** Avatar URL (头像). */
    private String avatar;
    /** Capability tags (能力标签). */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> capabilities;
    /** Agent system prompt (提示词). */
    private String prompt;
    /** Visibility: TEAM or PRIVATE. */
    private String visibility;
    /** Lifecycle state; only ACTIVE Agents are assignable. */
    private String status;
}
