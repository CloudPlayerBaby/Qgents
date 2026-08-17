package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.entity.TaskEntity;

import java.util.UUID;

/**
 * Data access for single-key tasks.
 */
@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {
    /**
     * 原子领取尚未开始的 Task，防止同一 Task 被多个编排器并发执行。
     * 正常编排触发（任务创建事件）专用：只认领 PLANNING/PENDING，RUNNING 期间拒绝重入。
     */
    @Update("update tasks set status='RUNNING',updated_at=UTC_TIMESTAMP(6) "
            + "where id=#{taskId} and project_id=#{projectId} and status in ('PLANNING','PENDING')")
    int claimForOrchestration(@Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

    /**
     * 原子认领一次续跑（用户重试失败步骤 / 恢复卡死任务）：从 PLANNING/PENDING/FAILED 认领到 RUNNING；
     * 对 RUNNING 状态仅当没有进行中的 TaskRun（崩溃遗留）才认领，正常执行中的任务拒绝——防并发双编排。
     */
    @Update("update tasks set status='RUNNING',updated_at=UTC_TIMESTAMP(6) "
            + "where id=#{taskId} and project_id=#{projectId} and "
            + "(status in ('PLANNING','PENDING','FAILED') "
            + " or (status='RUNNING' and not exists (select 1 from task_runs r where r.task_id=#{taskId} "
            + "and r.status in ('QUEUED','RUNNING','WAITING_INPUT','WAITING_APPROVAL'))))")
    int claimForResume(@Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

    /**
     * Locks one Task while changing execution or continuation state.
     */
    @Select("select * from tasks where id=#{taskId} for update")
    TaskEntity selectByIdForUpdate(UUID taskId);

    /**
     * 返回项目内当前最大的 display_code 数字序号（如 T-1024 返回 1024）；无任务时返回 null。
     * 调用方须在持有项目级锁的事务内使用，保证序号单调递增且不重复。
     */
    @Select("SELECT MAX(CAST(SUBSTRING_INDEX(display_code, '-', -1) AS UNSIGNED)) "
            + "FROM tasks WHERE project_id = #{projectId}")
    Long selectMaxDisplayCodeSeq(@Param("projectId") UUID projectId);

    /**
     * 统计某项目仓库绑定上仍在活动状态（PLANNING/PENDING/RUNNING/CANCELLING/WAITING_DIFF_CONFIRMATION/
     * DELIVERING）的任务数，供软解绑前的占用校验使用。任务与仓库通过 Workspace worktree 关联。
     */
    @Select("SELECT COUNT(*) FROM tasks t WHERE t.status IN "
            + "('PLANNING','PENDING','RUNNING','CANCELLING','WAITING_DIFF_CONFIRMATION','DELIVERING') "
            + "AND EXISTS (SELECT 1 FROM workspace_repositories wr "
            + "WHERE wr.workspace_id = t.workspace_id AND wr.project_repository_id = #{projectRepositoryId})")
    int countActiveTasksUsingRepository(@Param("projectRepositoryId") UUID projectRepositoryId);

    /**
     * 返回任务最近一次 SUCCEEDED 的 DEVELOPER 运行 ID（终态 Diff 批次兜底）；
     * 无成功 Coding 运行时返回 null。
     */
    @Select("select id from task_runs where task_id=#{taskId} and role='DEVELOPER' and status='SUCCEEDED' "
            + "order by created_at desc limit 1")
    UUID selectLastSucceededCodingRunId(@Param("taskId") UUID taskId);

    /**
     * 恢复调度器扫描疑似崩溃遗留的卡死任务：状态 PLANNING/PENDING/RUNNING 且超过阈值未更新，
     * 且没有任何进行中的 TaskRun（正常编排期间 task 有活跃 run，不会命中）。
     */
    @Select("select t.id from tasks t where t.status in ('PLANNING','PENDING','RUNNING') "
            + "and t.updated_at < #{staleBefore} "
            + "and not exists (select 1 from task_runs r where r.task_id=t.id "
            + "and r.status in ('QUEUED','RUNNING','WAITING_INPUT','WAITING_APPROVAL')) "
            + "order by t.updated_at limit #{limit}")
    java.util.List<UUID> selectStaleOrphaned(@Param("staleBefore") java.time.LocalDateTime staleBefore,
                                             @Param("limit") int limit);
}
