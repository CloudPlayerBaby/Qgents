package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import qg.qgent.entity.IdempotencyRecordEntity;

@Mapper
public interface IdempotencyRecordMapper extends BaseMapper<IdempotencyRecordEntity> {
}
