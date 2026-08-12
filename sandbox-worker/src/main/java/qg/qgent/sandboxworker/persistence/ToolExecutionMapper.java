package qg.qgent.sandboxworker.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 工具执行记录的数据访问接口。
 */
@Mapper
public interface ToolExecutionMapper extends BaseMapper<ToolExecutionEntity> {
    @Update("""
            UPDATE tool_executions
            SET status = 'INTERRUPTED', failure_reason = 'Worker 重启，执行状态已中断', finished_at = #{finishedAt}
            WHERE status IN ('QUEUED', 'RUNNING')
            """)
    int markInterrupted(LocalDateTime finishedAt);
}
