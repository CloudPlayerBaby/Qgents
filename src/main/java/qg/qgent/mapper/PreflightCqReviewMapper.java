package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import qg.qgent.entity.PreflightCqReviewEntity;

/** MR 创建前人工 CQ 审查记录。 */
@Mapper
public interface PreflightCqReviewMapper extends BaseMapper<PreflightCqReviewEntity> {
}
