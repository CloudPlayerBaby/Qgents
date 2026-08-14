package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
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
}
