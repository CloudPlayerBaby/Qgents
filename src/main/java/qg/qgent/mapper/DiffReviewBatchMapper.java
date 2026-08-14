package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.DiffReviewBatchEntity;

import java.util.UUID;

@Mapper
public interface DiffReviewBatchMapper extends BaseMapper<DiffReviewBatchEntity> {
    /** Locks an existing review batch before changing its review or delivery state. */
    @Select("select * from diff_review_batches where id=#{batchId} for update")
    DiffReviewBatchEntity selectByIdForUpdate(UUID batchId);
}
