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
    @Update("update tasks set status='RUNNING',failure_code=null,failure_reason=null,failure_retryable=null,"
            + "failure_occurred_at=null,updated_at=UTC_TIMESTAMP(6) "
            + "where id=#{taskId} and project_id=#{projectId} and status in ('PLANNING','PENDING')")
    int claimForOrchestration(@Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

    /**
     * 原子认领一次续跑（用户重试失败步骤 / 恢复卡死任务）：从 PLANNING/PENDING/FAILED 认领到 RUNNING；
     * 对 RUNNING 状态仅当没有进行中的 TaskRun（崩溃遗留）才认领，正常执行中的任务拒绝——防并发双编排。
     */
    @Update("update tasks set status='RUNNING',failure_code=null,failure_reason=null,failure_retryable=null,"
            + "failure_occurred_at=null,updated_at=UTC_TIMESTAMP(6) "
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
     * 编排心跳：仅刷新任务的 updated_at（规划期/长步骤执行期间使用），防止
     * {@code TaskRunRecoveryScheduler} 把仍在正常编排中的任务误判为崩溃卡死而触发续跑。
     * 不改变 status、不发任何事件；与恢复调度器 {@code updated_at < staleBefore} 的 UTC 语义一致。
     *
     * @param taskId 目标任务 ID
     * @return 影响行数（任务存在时为 1，否则为 0）
     */
    @Update("update tasks set updated_at=UTC_TIMESTAMP(6) where id=#{taskId}")
    int touchUpdatedAt(@Param("taskId") UUID taskId);

    /**
     * Workspace 被其他 Task 持有写租约时，将尚未产生运行的任务放回 PENDING 等待恢复器重试。
     * 该 CAS 不覆盖取消、交付或终态，避免租约冲突被误报为任务失败。
     */
    @Update("update tasks set status='PENDING', updated_at=UTC_TIMESTAMP(6) where id=#{taskId} "
            + "and project_id=#{projectId} and status in ('PLANNING','RUNNING','PENDING')")
    int deferForWorkspaceWriteLease(@Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

    /**
     * 将因陈旧 TaskRun 被恢复器回收的任务收敛为 FAILED。
     * 只允许覆盖仍处于编排启动态的任务，避免覆盖用户取消、交付或其他终态。
     */
    @Update("update tasks set status='FAILED', failure_code='TASK_RUN_TIMEOUT', "
            + "failure_reason='任务运行超时，执行器未在规定时间内返回', failure_retryable=1, "
            + "failure_occurred_at=UTC_TIMESTAMP(6), updated_at=UTC_TIMESTAMP(6) where id=#{taskId} "
            + "and project_id=#{projectId} and status in ('PLANNING','PENDING','RUNNING') "
            + "and not exists (select 1 from task_runs r where r.task_id=#{taskId} "
            + "and r.status in ('QUEUED','RUNNING','WAITING_INPUT','WAITING_APPROVAL'))")
    int failAfterStaleRun(@Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

    /**
     * 找出尚未创建活跃 Run、且关联 Workspace 当前没有有效写租约的 PENDING 任务。
     * 恢复器只发布续跑事件，实际认领仍由 claimForResume 的 CAS 完成。
     */
    @Select("select t.id from tasks t join workspaces w on w.id=t.workspace_id where t.status='PENDING' "
            + "and (w.write_lease_expires_at is null or w.write_lease_expires_at <= UTC_TIMESTAMP(6)) "
            + "and not exists (select 1 from task_runs r where r.task_id=t.id "
            + "and r.status in ('QUEUED','RUNNING','WAITING_INPUT','WAITING_APPROVAL')) "
            + "order by t.updated_at limit #{limit}")
    java.util.List<UUID> selectPendingWithAvailableWorkspaceLease(@Param("limit") int limit);

    /**
     * 返回项目内当前最大的 display_code 数字序号（如 T-1024 返回 1024）；无任务时返回 null。
     * 调用方须在持有项目级锁的事务内使用，保证序号单调递增且不重复。
     */
    @Select("SELECT MAX(CAST(SUBSTRING_INDEX(display_code, '-', -1) AS UNSIGNED)) "
            + "FROM tasks WHERE project_id = #{projectId}")
    Long selectMaxDisplayCodeSeq(@Param("projectId") UUID projectId);

    /**
     * 统计某项目仓库绑定上仍在活动状态（PLANNING/PENDING/RUNNING/CANCELLING/WAITING_DIFF_CONFIRMATION/
     * WAITING_PREFLIGHT/DELIVERING）的任务数，供软解绑前的占用校验使用。任务与仓库通过 Workspace worktree 关联。
     */
    @Select("SELECT COUNT(*) FROM tasks t WHERE t.status IN "
            + "('PLANNING','PENDING','RUNNING','CANCELLING','WAITING_DIFF_CONFIRMATION','WAITING_PREFLIGHT','DELIVERING') "
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
     * 收集同一 (repository, sourceBranch) 上已交付的任务，用于分支级预检的覆盖集合。
     * MR_FIRST 已完成全部仓库 Push 进入 WAITING_PREFLIGHT；DIFF_FIRST 已完成确认推送进入 SUCCEEDED。
     * 多个已交付任务连续提交到同一功能分支时，它们都作为该分支级 MR 的覆盖任务。
     */
    @Select("select t.id from tasks t join workspaces w on w.id=t.workspace_id "
            + "join workspace_repositories wr on wr.workspace_id=w.id "
            + "where t.project_id=#{projectId} and wr.project_repository_id=#{repositoryId} "
            + "and wr.source_branch=#{sourceBranch} "
            + "and ((t.delivery_mode='MR_FIRST' and t.status in ('WAITING_PREFLIGHT','SUCCEEDED')) "
            + "or (t.delivery_mode='DIFF_FIRST' and t.status='SUCCEEDED')) "
            + "order by t.created_at")
    java.util.List<UUID> selectDeliveredTasksOnBranch(@Param("projectId") UUID projectId,
                                                      @Param("repositoryId") UUID repositoryId,
                                                      @Param("sourceBranch") String sourceBranch);

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
