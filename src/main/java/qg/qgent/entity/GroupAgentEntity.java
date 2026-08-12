package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 需求群 Agent 参与者关系实体，对应表 group_agents。
 * <p>
 * Agent 首次通过 sendAsAgent 向群回消息时自动成为群参与者；复合主键 (requirement_group_id, agent_id)。
 */
@Data
@TableName("group_agents")
public class GroupAgentEntity {

    /** 需求群 ID（UUIDv7，BINARY(16)）。 */
    private UUID requirementGroupId;

    /** Agent ID（UUIDv7，BINARY(16)）。 */
    private UUID agentId;

    /** 加入时间（UTC）。 */
    private LocalDateTime joinedAt;
}
