package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.MrPreflightRequestEntity;

import java.util.List;
import java.util.UUID;

@Mapper
public interface MrPreflightRequestMapper extends BaseMapper<MrPreflightRequestEntity> {
    @Select("select * from mr_preflight_requests where id=#{id} for update")
    MrPreflightRequestEntity selectByIdForUpdate(@Param("id") UUID id);

    @Select("select * from mr_preflight_requests where context_hash=#{contextHash} limit 1")
    MrPreflightRequestEntity selectByContextHash(@Param("contextHash") String contextHash);

    @Select("select * from mr_preflight_requests where project_id=#{projectId} and trigger_task_id=#{taskId} "
            + "order by created_at desc limit 1")
    MrPreflightRequestEntity selectLatestByTask(@Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

    /** 任务按仓库获取全部预检申请（多仓库任务逐仓库创建，最新在前）。 */
    @Select("select * from mr_preflight_requests where project_id=#{projectId} and trigger_task_id=#{taskId} "
            + "and project_repository_id=#{repositoryId} order by created_at desc limit 1")
    MrPreflightRequestEntity selectLatestByTaskAndRepository(@Param("projectId") UUID projectId,
                                                             @Param("taskId") UUID taskId,
                                                             @Param("repositoryId") UUID repositoryId);

    /** 返回指定任务的全部预检申请（多仓库逐条返回，最新在前）。 */
    @Select("select * from mr_preflight_requests where project_id=#{projectId} and trigger_task_id=#{taskId} "
            + "order by created_at desc")
    List<MrPreflightRequestEntity> selectListByTask(@Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

    /** 恢复调度器扫描：等待 Dry Run 或等待创建 MR 的预检申请（MR_CREATED/STALE 终态除外）。 */
    @Select("select * from mr_preflight_requests where status in "
            + "('REQUESTED','DRY_RUN_QUEUED','DRY_RUN_RUNNING','WAITING_CQ','CREATING_MR') "
            + "order by updated_at asc limit #{limit}")
    List<MrPreflightRequestEntity> selectRecoverable(@Param("limit") int limit);
}
