package qg.qgent.sandboxworker.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工具执行记录的数据访问接口。
 */
@Mapper
public interface ToolExecutionMapper extends BaseMapper<ToolExecutionEntity> {
    @Update("""
            UPDATE tool_executions
            SET status = 'INTERRUPTED', failure_code = 'WORKER_RESTART_INTERRUPTED',
                failure_reason = 'Worker 重启，执行状态已中断', finished_at = #{finishedAt}
            WHERE owner_worker_id = #{workerId} AND status IN ('QUEUED', 'RUNNING')
            """)
    int markInterrupted(@Param("workerId") String workerId, @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
            UPDATE tool_executions
            SET status = 'RUNNING', started_at = #{startedAt}
            WHERE id = #{id} AND owner_worker_id = #{workerId} AND status = 'QUEUED'
            """)
    int markRunning(@Param("id") String id, @Param("workerId") String workerId,
                    @Param("startedAt") LocalDateTime startedAt);

    @Update("""
            UPDATE tool_executions
            SET status = 'CANCELLED', failure_code = 'EXECUTION_CANCELLED',
                failure_reason = '执行已取消', finished_at = #{finishedAt}
            WHERE id = #{id} AND owner_worker_id = #{workerId} AND status IN ('QUEUED', 'RUNNING')
            """)
    int markCancelled(@Param("id") String id, @Param("workerId") String workerId,
                      @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
            UPDATE tool_executions
            SET status = #{status}, exit_code = #{exitCode}, result_json = #{resultJson},
                failure_code = #{failureCode}, failure_reason = #{failureReason}, finished_at = #{finishedAt}
            WHERE id = #{id} AND owner_worker_id = #{workerId} AND status = 'RUNNING'
            """)
    int finishIfRunning(@Param("id") String id, @Param("workerId") String workerId,
                        @Param("status") String status,
                        @Param("exitCode") Integer exitCode, @Param("resultJson") String resultJson,
                        @Param("failureCode") String failureCode, @Param("failureReason") String failureReason,
                        @Param("finishedAt") LocalDateTime finishedAt);

    @Update("""
            UPDATE tool_executions
            SET status = 'FAILED', failure_code = #{failureCode}, failure_reason = #{failureReason},
                finished_at = #{finishedAt}
            WHERE id = #{id} AND owner_worker_id = #{workerId} AND status = 'QUEUED'
            """)
    int rejectQueued(@Param("id") String id, @Param("workerId") String workerId,
                     @Param("failureCode") String failureCode, @Param("failureReason") String failureReason,
                     @Param("finishedAt") LocalDateTime finishedAt);

    @Select("""
            SELECT id FROM tool_executions
            WHERE sandbox_id = #{sandboxId} AND owner_worker_id = #{workerId}
              AND status IN ('QUEUED', 'RUNNING')
            """)
    List<String> selectActiveIdsBySandbox(@Param("sandboxId") String sandboxId,
                                          @Param("workerId") String workerId);
}
