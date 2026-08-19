package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.DiffFileEntity;

import java.util.List;
import java.util.UUID;

@Mapper
public interface DiffFileMapper extends BaseMapper<DiffFileEntity> {
    /**
     * 群聊卡片只读取文件标签元数据，刻意不选择 hunks JSON，避免展开一个 Diff 时反序列化所有文件内容。
     */
    @Select("select id, diff_id, sequence_no, path, change_type, additions, deletions, binary_flag, created_at "
            + "from diff_files where diff_id=#{diffId} order by sequence_no asc limit #{limit}")
    List<DiffFileEntity> selectPreviewFileSummaries(@Param("diffId") UUID diffId, @Param("limit") int limit);
}
