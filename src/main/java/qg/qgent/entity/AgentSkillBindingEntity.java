package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Agent-Skill 绑定关系实体，对应表 agent_skill_bindings。
 * <p>
 * 复合主键 (project_id, agent_id, skill_id)：同一 Agent 在不同项目可绑定不同技能集；
 * skill 外键同时约束 Skill 必须属于该项目的 skills 表（skills.project_id 一致）。
 * PUT 全量替换语义下，绑定集以删除再插入维护，不产生历史版本。
 */
@Data
@TableName("agent_skill_bindings")
public class AgentSkillBindingEntity {

    /**
     * 所属项目 ID（UUIDv7，BINARY(16)）。
     */
    private UUID projectId;

    /**
     * Team 级 Agent ID（UUIDv7，BINARY(16)）。
     */
    private UUID agentId;

    /**
     * 项目内 Skill ID（UUIDv7，BINARY(16)）。
     */
    private UUID skillId;

    /**
     * 绑定发起用户 ID（UUIDv7，BINARY(16)）。
     */
    private UUID createdBy;

    /**
     * 绑定时间（UTC）。
     */
    private LocalDateTime createdAt;
}
