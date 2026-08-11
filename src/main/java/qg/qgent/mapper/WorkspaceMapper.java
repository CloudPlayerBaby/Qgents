package qg.qgent.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.WorkspaceEntity;
import java.util.UUID;
/** Data access for single-key task workspaces. */
@Mapper
public interface WorkspaceMapper extends BaseMapper<WorkspaceEntity> {
    /** Locks the task workspace to serialize step sequence allocation and writers. */
    @Select("select * from workspaces where task_id=#{taskId} for update")
    WorkspaceEntity selectByTaskForUpdate(UUID taskId);
}
