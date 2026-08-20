package qg.qgent.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.MrPreflightTaskEntity;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MrPreflightTaskMapper {
    @Insert("insert into mr_preflight_tasks(preflight_id,task_id,role) values(#{preflightId},#{taskId},#{role})")
    int insertLink(@Param("preflightId") UUID preflightId, @Param("taskId") UUID taskId,
                   @Param("role") String role);

    @Select("select * from mr_preflight_tasks where preflight_id=#{preflightId} order by task_id")
    List<MrPreflightTaskEntity> selectByPreflight(@Param("preflightId") UUID preflightId);
}
