package qg.qgent.mapper;
import org.apache.ibatis.annotations.*;
import java.util.UUID;
import java.util.List;
import qg.qgent.entity.TaskStepRepositoryEntity;
/** Explicit data access for composite-key step repository scopes. */
@Mapper public interface TaskStepRepositoryMapper {
 @Insert("insert into task_step_repositories(task_step_id,project_repository_id,access_mode) values(#{stepId},#{repositoryId},#{accessMode})") int insertLink(@Param("stepId") UUID stepId,@Param("repositoryId") UUID repositoryId,@Param("accessMode") String accessMode);
 @Select("select task_step_id,project_repository_id,access_mode from task_step_repositories where task_step_id=#{stepId}") List<TaskStepRepositoryEntity> selectByStep(UUID stepId);
 /** 批量查询多个步骤的仓库范围（任务步骤列表摘要用，避免逐步骤 N+1）。 */
 @Select({"<script>",
   "select task_step_id,project_repository_id,access_mode from task_step_repositories where task_step_id in",
   "<foreach collection='stepIds' item='sid' open='(' separator=',' close=')'>#{sid}</foreach>",
   "</script>"})
 List<TaskStepRepositoryEntity> selectByStepIds(@Param("stepIds") List<UUID> stepIds);
}
