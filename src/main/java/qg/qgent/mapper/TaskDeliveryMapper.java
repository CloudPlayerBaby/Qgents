package qg.qgent.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.TaskDeliveryEntity;
/** Data access for task-level delivery decisions. */
@Mapper
public interface TaskDeliveryMapper extends BaseMapper<TaskDeliveryEntity> {
    /** Returns the highest delivery version, or zero before the first delivery. */
    @Select("select coalesce(max(version),0) from task_deliveries where task_id=#{taskId}")
    Integer maxVersion(java.util.UUID taskId);
}
