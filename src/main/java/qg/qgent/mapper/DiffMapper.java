package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.DiffEntity;

@Mapper
public interface DiffMapper extends BaseMapper<DiffEntity> {
    @Select("select * from diffs where id=#{id} for update")
    DiffEntity selectByIdForUpdate(java.util.UUID id);

    @Select("select d.* from diffs d left join diff_review_batches b on b.id=d.review_batch_id "
            + "where d.task_id=#{taskId} and d.project_id=#{projectId} and d.workspace_id=#{workspaceId} "
            + "and d.project_repository_id=#{repositoryId} and d.head_commit=#{headCommit} "
            + "and d.status='ACCEPTED' and d.delivery_status in ('COMMITTED','MR_CREATED') "
            + "and (d.review_batch_id is null or (b.task_id=d.task_id and b.workspace_id=d.workspace_id "
            + "and b.review_status='ACCEPTED' and b.delivery_status in "
            + "('DELIVERING','PARTIALLY_DELIVERED','DELIVERED','FAILED'))) "
            + "order by d.created_at desc limit 1 for update")
    DiffEntity selectAcceptedCommittedForMr(@Param("taskId") java.util.UUID taskId,
                                            @Param("projectId") java.util.UUID projectId, @Param("workspaceId") java.util.UUID workspaceId,
                                            @Param("repositoryId") java.util.UUID repositoryId, @Param("headCommit") String headCommit);

    /**
     * 交付成功时置 MR_CREATED 并显式清空失败标记。MyBatis-Plus 的 updateById 会忽略 null 字段，
     * 因此必须用显式 SQL 把 failure_code/failure_reason 置空。
     */
    @org.apache.ibatis.annotations.Update("update diffs set delivery_status='MR_CREATED', "
            + "delivery_failure_code=null, delivery_failure_reason=null, updated_at=#{updatedAt} "
            + "where id=#{id}")
    int markDelivered(@Param("id") java.util.UUID id, @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
