package qg.qgent.mapper;
import org.apache.ibatis.annotations.*;
import java.util.UUID;
import java.util.List;
import qg.qgent.entity.TaskStepDependencyEntity;
/** Explicit data access for composite-key step dependencies. */
@Mapper public interface TaskStepDependencyMapper {
 @Insert("insert into task_step_dependencies(task_step_id,depends_on_task_step_id) values(#{stepId},#{dependsOnId})") int insertLink(@Param("stepId") UUID stepId,@Param("dependsOnId") UUID dependsOnId);
 @Select("select depends_on_task_step_id from task_step_dependencies where task_step_id=#{stepId} order by depends_on_task_step_id")
 List<UUID> selectDependsOnIds(@Param("stepId") UUID stepId);
 /** 批量查询多个步骤的全部依赖边（任务步骤列表摘要用，避免逐步骤 N+1）。 */
 @Select({"<script>",
   "select task_step_id, depends_on_task_step_id from task_step_dependencies where task_step_id in",
   "<foreach collection='stepIds' item='sid' open='(' separator=',' close=')'>#{sid}</foreach>",
   "order by depends_on_task_step_id",
   "</script>"})
 List<TaskStepDependencyEntity> selectByStepIds(@Param("stepIds") List<UUID> stepIds);
}
