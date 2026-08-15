package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import qg.qgent.entity.TaskAcceptanceCriterionEntity;

/**
 * Data access for single-key task-level acceptance criteria.
 */
@Mapper
public interface TaskAcceptanceCriterionMapper extends BaseMapper<TaskAcceptanceCriterionEntity> {
}
