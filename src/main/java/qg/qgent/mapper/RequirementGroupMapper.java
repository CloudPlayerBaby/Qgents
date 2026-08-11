package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.RequirementGroupEntity;

import java.util.List;
import java.util.UUID;

/**
 * 需求群数据访问。
 */
@Mapper
public interface RequirementGroupMapper extends BaseMapper<RequirementGroupEntity> {

    /**
     * 项目全部群（含主群与已归档），按最近活跃排序；从未发言的群以创建时间兜底。
     *
     * @param projectId 项目 ID
     * @return 排序后的群列表
     */
    @Select("select * from requirement_groups where project_id=#{projectId}"
            + " order by coalesce(last_message_at, created_at) desc")
    List<RequirementGroupEntity> listByProject(UUID projectId);
}
