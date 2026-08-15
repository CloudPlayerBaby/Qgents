package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.ProjectRepositoryEntity;

@Mapper
public interface ProjectRepositoryMapper extends BaseMapper<ProjectRepositoryEntity> {
    /**
     * 串行化同一仓库的 MR 创建领取，避免两个不同操作同时创建活动 PR。
     */
    @Select("select * from project_repositories where id=#{id} for update")
    ProjectRepositoryEntity selectByIdForUpdate(java.util.UUID id);
}
