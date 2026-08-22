package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
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
     * 任务中心列表只需要每个步骤的最新运行，避免把整个任务运行历史加载到内存。
     * task_runs.id 为 UUIDv7，created_at 作为并列时的稳定兜底排序。
     */
    @Select({"<script>",
            "select * from (",
            "select tr.*, row_number() over (partition by tr.task_id, tr.task_step_id ",
            "order by tr.created_at desc, tr.id desc) as row_num ",
            "from task_runs tr where tr.task_id in ",
            "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>#{taskId}</foreach>",
            ") latest where latest.row_num = 1",
            "</script>"})
    List<TaskRunEntity> selectLatestForTaskList(@Param("taskIds") List<UUID> taskIds);

    /** 失败提示需要最新失败运行，但不应为此重新加载全部历史运行。 */
    @Select({"<script>",
            "select * from (",
            "select tr.*, row_number() over (partition by tr.task_id "
                    + "order by coalesce(tr.failure_occurred_at, tr.updated_at) desc, tr.id desc) as row_num ",
            "from task_runs tr where tr.status = 'FAILED' and tr.task_id in ",
            "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>#{taskId}</foreach>",
            ") latest where latest.row_num = 1",
            "</script>"})
    List<TaskRunEntity> selectLatestFailedForTaskList(@Param("taskIds") List<UUID> taskIds);

    /** 查找同一失败运行已经创建的活动重试，避免网络重试重复创建 TaskRun。 */
    @Select("select * from task_runs where task_id=#{taskId} and task_step_id=#{taskStepId} "
            + "and retry_of_task_run_id=#{retryOfTaskRunId} and status in ('QUEUED','RUNNING') "
            + "order by created_at desc limit 1")
    TaskRunEntity selectActiveRetry(@Param("taskId") UUID taskId,
                                    @Param("taskStepId") UUID taskStepId,
                                    @Param("retryOfTaskRunId") UUID retryOfTaskRunId);

    /** 按 TaskRun 串行化日志序号分配，避免并发 Worker/Agent 输出撞唯一键。 */
    @Select("select * from task_runs where id = #{runId} for update")
    TaskRunEntity selectByIdForUpdate(@Param("runId") UUID runId);

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
    @Update("update task_runs set status='FAILED', failure_code='TASK_RUN_TIMEOUT', "
            + "failure_reason='运行超时，执行器未在规定时间内返回', failure_occurred_at=UTC_TIMESTAMP(6), "
            + "finished_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6) "
            + "where id=#{runId} and status in ('QUEUED','RUNNING') and updated_at < #{staleBefore}")
    int reclaimStaleRun(@Param("runId") UUID runId, @Param("staleBefore") java.time.LocalDateTime staleBefore);

    /**
     * 解散团队时按项目逐层删除不再被任何运行引用的叶子运行。
     * <p>
     * task_runs 存在自引用外键 {@code fk_task_run_retry}（retry_of_task_run_id -&gt; id）且无级联，
     * 重试链可多级；单条批量 DELETE 无法在同一语句内删除互相引用的父子行，须循环调用
     * 本方法直至返回 0。重试引用限定在相同项目内，保证逐层收敛。
     * <p>
     * MySQL 不允许在 DELETE 的 FROM/子查询中再次读取目标表（error 1093），因此把
     * 「被引用的父行 id」子查询用派生表 {@code AS tmp} 包一层，让 MySQL 先物化；
     * 同时用 {@code IS NOT NULL} 排除空值，避免 {@code NOT IN} 遇到 NULL 时整体失效。
     *
     * @param projectId 项目 ID
     * @return 本次实际删除的行数；为 0 表示已无满足条件的运行
     */
    @Delete("delete from task_runs where project_id = #{projectId} "
            + "and id not in (select rid from (select retry_of_task_run_id as rid from task_runs "
            + "where project_id = #{projectId} and retry_of_task_run_id is not null) as tmp)")
    int deleteUnreferencedRuns(@Param("projectId") UUID projectId);
}
