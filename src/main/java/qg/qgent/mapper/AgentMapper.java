package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import qg.qgent.entity.AgentEntity;

/**
 * Data access for team-scoped Agent identities.
 */
@Mapper
public interface AgentMapper extends BaseMapper<AgentEntity> {
}
