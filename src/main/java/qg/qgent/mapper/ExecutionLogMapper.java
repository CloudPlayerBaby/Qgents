package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.ExecutionLogEntity;

import java.util.UUID;

@Mapper
public interface ExecutionLogMapper extends BaseMapper<ExecutionLogEntity> {

    /** 在当前事务内锁定运行日志的尾部，避免并发追加产生重复 sequence。 */
    @Select("SELECT COALESCE(MAX(sequence_no), 0) FROM execution_logs WHERE task_run_id = #{taskRunId} FOR UPDATE")
    long nextSequence(UUID taskRunId);
}
