package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import qg.qgent.entity.DiffReviewBatchEntity;

import java.util.UUID;
import java.util.List;

@Mapper
public interface DiffReviewBatchMapper extends BaseMapper<DiffReviewBatchEntity> {
    /**
     * Locks an existing review batch before changing its review or delivery state.
     */
    @Select("select * from diff_review_batches where id=#{batchId} for update")
    DiffReviewBatchEntity selectByIdForUpdate(UUID batchId);

    /**
     * Returns and locks the one review batch that is currently awaiting a
     * decision for a Workspace.  Workspace serialization makes this query the
     * authoritative "latest pending Diff" check for continuation and confirm.
     */
    @Select("select * from diff_review_batches where workspace_id=#{workspaceId} "
            + "and review_status='PENDING_CONFIRMATION' order by created_at desc, id desc limit 1 for update")
    List<DiffReviewBatchEntity> selectPendingByWorkspaceForUpdate(@Param("workspaceId") UUID workspaceId);
}
