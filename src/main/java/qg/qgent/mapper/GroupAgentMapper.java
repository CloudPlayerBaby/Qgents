package qg.qgent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 需求群 Agent 参与者关系数据访问（复合主键，使用自定义 SQL）。
 */
@Mapper
public interface GroupAgentMapper {

    /**
     * 将 Agent 加入群（幂等：已存在则忽略，复合主键去重）。
     *
     * @param groupId 需求群 ID
     * @param agentId Agent ID
     * @return 影响行数（0 表示已存在）
     */
    @Insert("INSERT IGNORE INTO group_agents(requirement_group_id, agent_id) VALUES(#{groupId}, #{agentId})")
    int insertAgent(@Param("groupId") UUID groupId, @Param("agentId") UUID agentId);

    /**
     * 查询群内参与聊天的 Agent ID 列表。
     *
     * @param groupId 需求群 ID
     * @return Agent ID 列表
     */
    @Select("SELECT agent_id FROM group_agents WHERE requirement_group_id = #{groupId}")
    List<UUID> selectAgentIds(@Param("groupId") UUID groupId);
}
