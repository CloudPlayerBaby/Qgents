package qg.qgent.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.TaskEntity;
import java.util.UUID;
/** Data access for single-key tasks. */
@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {
    /** Locks one Task while allocating a delivery version. */
    @Select("select * from tasks where id=#{taskId} for update")
    TaskEntity selectByIdForUpdate(UUID taskId);
}
