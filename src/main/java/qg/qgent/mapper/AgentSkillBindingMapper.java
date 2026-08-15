package qg.qgent.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.UUID;

/**
 * Agent-Skill 绑定关系数据访问（复合主键，使用自定义 SQL）。
 */
@Mapper
public interface AgentSkillBindingMapper {

    /**
     * 查询指定 Agent 在当前项目的已绑定 Skill ID 列表（按绑定时间正序）。
     *
     * @param projectId 项目 ID
     * @param agentId   Agent ID
     * @return 已绑定 Skill ID 列表
     */
    @Select("SELECT skill_id FROM agent_skill_bindings WHERE project_id = #{projectId} AND agent_id = #{agentId} "
            + "ORDER BY created_at ASC")
    List<UUID> selectSkillIds(@Param("projectId") UUID projectId, @Param("agentId") UUID agentId);

    /**
     * 全量替换：删除指定 Agent 在当前项目的全部绑定。
     *
     * @param projectId 项目 ID
     * @param agentId   Agent ID
     * @return 删除行数
     */
    @Delete("DELETE FROM agent_skill_bindings WHERE project_id = #{projectId} AND agent_id = #{agentId}")
    int deleteByAgent(@Param("projectId") UUID projectId, @Param("agentId") UUID agentId);

    /**
     * 插入一条绑定；复合主键冲突时抛出 DuplicateKeyException（调用方已在服务层去重）。
     *
     * @param projectId 项目 ID
     * @param agentId   Agent ID
     * @param skillId   Skill ID
     * @param createdBy 绑定发起用户 ID
     * @return 影响行数
     */
    @Insert("INSERT INTO agent_skill_bindings(project_id, agent_id, skill_id, created_by) "
            + "VALUES(#{projectId}, #{agentId}, #{skillId}, #{createdBy})")
    int insertBinding(@Param("projectId") UUID projectId, @Param("agentId") UUID agentId,
                      @Param("skillId") UUID skillId, @Param("createdBy") UUID createdBy);
}
