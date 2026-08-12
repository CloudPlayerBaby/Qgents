package qg.qgent.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.WorkspaceEntity;
import java.util.UUID;
/** Data access for persistent project workspaces. */
@Mapper
public interface WorkspaceMapper extends BaseMapper<WorkspaceEntity> {
    /** Locks a workspace to serialize writers across continuation tasks. */
    @Select("select * from workspaces where id=#{workspaceId} for update")
    WorkspaceEntity selectByIdForUpdate(UUID workspaceId);
}
