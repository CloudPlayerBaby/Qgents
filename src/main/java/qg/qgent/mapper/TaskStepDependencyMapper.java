package qg.qgent.mapper;
import org.apache.ibatis.annotations.*;
import java.util.UUID;
/** Explicit data access for composite-key step dependencies. */
@Mapper public interface TaskStepDependencyMapper {
 @Insert("insert into task_step_dependencies(task_step_id,depends_on_task_step_id) values(#{stepId},#{dependsOnId})") int insertLink(@Param("stepId") UUID stepId,@Param("dependsOnId") UUID dependsOnId);
}
