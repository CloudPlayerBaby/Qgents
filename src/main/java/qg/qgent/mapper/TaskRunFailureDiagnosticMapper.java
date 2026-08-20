package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import qg.qgent.entity.TaskRunFailureDiagnosticEntity;

/** TaskRun 基础设施失败内部诊断的数据访问。 */
@Mapper
public interface TaskRunFailureDiagnosticMapper extends BaseMapper<TaskRunFailureDiagnosticEntity> {
}
