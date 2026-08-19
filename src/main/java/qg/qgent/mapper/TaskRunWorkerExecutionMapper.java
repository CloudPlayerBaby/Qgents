package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import qg.qgent.entity.TaskRunWorkerExecutionEntity;

/** TaskRun 与 Worker 工具执行诊断关联的数据访问。 */
@Mapper
public interface TaskRunWorkerExecutionMapper extends BaseMapper<TaskRunWorkerExecutionEntity> {
}
