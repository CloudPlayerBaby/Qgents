package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.DiffEntity;

import java.util.List;
import java.util.UUID;

@Mapper
public interface DiffMapper extends BaseMapper<DiffEntity> {
    @Select("select * from diffs where id=#{id} for update")
    DiffEntity selectByIdForUpdate(UUID id);

    /**
     * 返回分支级预检覆盖的已交付 Diff：这些任务的接受 Diff 已被 Commit 并 Push，属于该分支级 MR 的累计内容。
     */
    @Select("<script>select d.id from diffs d where d.project_id=#{projectId} "
            + "and d.project_repository_id=#{repositoryId} and d.delivery_status in ('PUSHED','MR_CREATED') "
            + "and d.task_id in <foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>#{taskId}</foreach> "
            + "order by d.created_at</script>")
    List<UUID> selectDeliveredDiffIds(@Param("projectId") UUID projectId,
                                      @Param("repositoryId") UUID repositoryId,
                                      @Param("taskIds") List<UUID> taskIds);

    @Select("select d.* from diffs d left join diff_review_batches b on b.id=d.review_batch_id "
            + "where d.task_id=#{taskId} and d.project_id=#{projectId} and d.workspace_id=#{workspaceId} "
            + "and d.project_repository_id=#{repositoryId} and d.head_commit=#{headCommit} "
            + "and d.status='ACCEPTED' and d.delivery_status in ('PUSHED','MR_CREATED') "
            + "and (d.review_batch_id is null or (b.task_id=d.task_id and b.workspace_id=d.workspace_id "
            + "and b.review_status='ACCEPTED' and b.delivery_status in "
            + "('DELIVERING','PARTIALLY_DELIVERED','DELIVERED','FAILED'))) "
            + "order by d.created_at desc limit 1 for update")
    DiffEntity selectAcceptedCommittedForMr(@Param("taskId") java.util.UUID taskId,
                                            @Param("projectId") java.util.UUID projectId, @Param("workspaceId") java.util.UUID workspaceId,
                                            @Param("repositoryId") java.util.UUID repositoryId, @Param("headCommit") String headCommit);

    /**
     * 推送前校验当前 HEAD 属于本任务已确认且已创建 Commit 的 Diff。
     * <p>
     * 首次 Push 时交付状态必然还是 COMMITTED，不能复用创建 MR 所需的 PUSHED/MR_CREATED 校验，
     * 否则会形成“必须已推送才能推送”的循环依赖。
     */
    @Select("select d.* from diffs d left join diff_review_batches b on b.id=d.review_batch_id "
            + "where d.task_id=#{taskId} and d.project_id=#{projectId} and d.workspace_id=#{workspaceId} "
            + "and d.project_repository_id=#{repositoryId} and d.head_commit=#{headCommit} "
            + "and d.status='ACCEPTED' and d.delivery_status in ('COMMITTED','PUSHED','MR_CREATED') "
            + "and (d.review_batch_id is null or (b.task_id=d.task_id and b.workspace_id=d.workspace_id "
            + "and b.review_status='ACCEPTED' and b.delivery_status in "
            + "('DELIVERING','PARTIALLY_DELIVERED','DELIVERED','FAILED'))) "
            + "order by d.created_at desc limit 1 for update")
    DiffEntity selectAcceptedCommittedForPush(@Param("taskId") java.util.UUID taskId,
                                              @Param("projectId") java.util.UUID projectId, @Param("workspaceId") java.util.UUID workspaceId,
                                              @Param("repositoryId") java.util.UUID repositoryId, @Param("headCommit") String headCommit);

    /**
     * 真实推送成功时置 PUSHED 并显式清空失败标记。MyBatis-Plus 的 updateById 会忽略 null 字段，
     * 因此必须用显式 SQL 把 failure_code/failure_reason 置空。
     */
    @org.apache.ibatis.annotations.Update("update diffs set delivery_status='MR_CREATED', "
            + "delivery_failure_code=null, delivery_failure_reason=null, updated_at=#{updatedAt} "
            + "where id=#{id}")
    int markDelivered(@Param("id") java.util.UUID id, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    @org.apache.ibatis.annotations.Update("update diffs set delivery_status='PUSHED', "
            + "delivery_failure_code=null, delivery_failure_reason=null, updated_at=#{updatedAt} "
            + "where id=#{id}")
    int markPushed(@Param("id") java.util.UUID id, @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
