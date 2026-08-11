package qg.qgent.mapper;
import org.apache.ibatis.annotations.*;
import java.util.UUID;
import java.util.List;
import qg.qgent.entity.TaskStepRepositoryEntity;
/** Explicit data access for composite-key step repository scopes. */
@Mapper public interface TaskStepRepositoryMapper {
 @Insert("insert into task_step_repositories(task_step_id,project_repository_id,access_mode) values(#{stepId},#{repositoryId},#{accessMode})") int insertLink(@Param("stepId") UUID stepId,@Param("repositoryId") UUID repositoryId,@Param("accessMode") String accessMode);
 @Select("select task_step_id,project_repository_id,access_mode from task_step_repositories where task_step_id=#{stepId}") List<TaskStepRepositoryEntity> selectByStep(UUID stepId);
}
