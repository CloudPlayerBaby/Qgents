package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.UUID;

/**
 * Team-scoped Agent identity used for safe workflow assignment.
 */
@Data
@TableName(value = "agents", autoResultMap = true)
public class AgentEntity {
    /**
     * UUIDv7 Agent identifier.
     */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * Team that owns the Agent.
     */
    private UUID teamId;
    /**
     * Optional creating user for PRIVATE visibility checks.
     */
    private UUID createdBy;
    /**
     * Display name shown in workflow configuration (昵称).
     */
    private String name;
    /**
     * Declared role matched against TaskStep.role (角色标签).
     */
    private String role;
    /**
     * Avatar URL (头像).
     */
    private String avatar;
    /**
     * Agent 用途描述（干什么/有什么用，展示与选用决策依据）。
     */
    private String description;
    /**
     * Agent system prompt (提示词).
     */
    private String prompt;
    /**
     * 发布审核拒绝原因（Team Owner 填写；批准为空）。仅创建者可见。
     */
    private String reviewReason;
    /**
     * 发布审核人（Team Owner）ID。
     */
    private UUID reviewedBy;
    /**
     * 发布审核时间（UTC）。
     */
    private java.time.LocalDateTime reviewedAt;
    /**
     * Visibility: TEAM / PENDING / PRIVATE.
     * PRIVATE（默认，仅创建者）→ PENDING（提交发布审核）→ TEAM（批准共享，不可回私有）| PRIVATE（拒绝，可重新提交）。
     */
    private String visibility;
    /**
     * Lifecycle state; only ACTIVE Agents are assignable.
     */
    private String status;
    /**
     * 是否为团队默认 Agent（系统预置）：每个团队每个角色至多一条，
     * 由 uk_agents_team_default_role 唯一索引在 DB 层保证；自定义 Agent 恒为 false。
     */
    private Boolean isDefault;
    /**
     * 创建时间（UTC）。
     */
    private java.time.LocalDateTime createdAt;
    /**
     * 更新时间（UTC）。
     */
    private java.time.LocalDateTime updatedAt;
}
