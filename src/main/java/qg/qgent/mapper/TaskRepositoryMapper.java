package qg.qgent.mapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.entity.TaskRepositoryEntity;
import java.util.*;
/** Explicit data access for the composite-key task/repository relation. */
@Mapper public interface TaskRepositoryMapper {
 @Insert("insert into task_repositories(task_id,project_repository_id,workspace_path,base_ref) values(#{taskId},#{repositoryId},#{workspacePath},#{baseRef})")
 int insertLink(@Param("taskId") UUID taskId,@Param("repositoryId") UUID repositoryId,@Param("workspacePath") String workspacePath,@Param("baseRef") String baseRef);
 @Select("select task_id,project_repository_id,workspace_path,base_ref,created_at from task_repositories where task_id=#{taskId}")
 List<TaskRepositoryEntity> selectByTask(UUID taskId);
}
