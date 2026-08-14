package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.MergeRequestEntity;

@Mapper
public interface MergeRequestMapper extends BaseMapper<MergeRequestEntity> {
    @Select("select * from merge_requests where id=#{id} for update")
    MergeRequestEntity selectByIdForUpdate(@Param("id") java.util.UUID id);
}
