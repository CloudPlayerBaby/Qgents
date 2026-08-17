package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.entity.TaskRunEntity;

import java.util.List;
import java.util.UUID;

@Mapper
public interface TaskRunMapper extends BaseMapper<TaskRunEntity> {

    /**
     * 恢复器：找出长期处于 QUEUED/RUNNING 的陈旧运行（其线程可能因 Worker 挂起而永远不返回）。
     * updated_at 早于阈值的才算陈旧；返回运行 ID，供 {@link #reclaimStaleRun} 原子回收。
     */
    @Select("select id from task_runs where status in ('QUEUED','RUNNING') "
            + "and updated_at < #{staleBefore} order by updated_at limit #{limit}")
    List<UUID> selectStaleRuns(@Param("staleBefore") java.time.LocalDateTime staleBefore, @Param("limit") int limit);

    /**
     * 原子回收一条陈旧运行：仅当仍处于 QUEUED/RUNNING 且早于阈值时才置 FAILED（CAS）。
     * 返回 1 表示本执行者抢到回收权；0 表示已被他人回收/已进入终态。翻出 RUNNING 后，
     * 旧线程晚回写终态会被完成逻辑以 RUNNING 守卫拒绝，从而不影响新的重试结果。
     */
    @Update("update task_runs set status='FAILED', finished_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6) "
            + "where id=#{runId} and status in ('QUEUED','RUNNING') and updated_at < #{staleBefore}")
    int reclaimStaleRun(@Param("runId") UUID runId, @Param("staleBefore") java.time.LocalDateTime staleBefore);
}
