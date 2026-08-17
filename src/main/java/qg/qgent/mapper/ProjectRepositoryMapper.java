package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.ProjectRepositoryEntity;

import java.util.UUID;

@Mapper
public interface ProjectRepositoryMapper extends BaseMapper<ProjectRepositoryEntity> {
    /**
     * 串行化同一仓库的 MR 创建领取，避免两个不同操作同时创建活动 PR。
     */
    @Select("select * from project_repositories where id=#{id} for update")
    ProjectRepositoryEntity selectByIdForUpdate(java.util.UUID id);

    /**
     * 按项目与 GitHub 仓库镜像加行锁查找绑定记录：串行化同一 (project_id, repository_id)
     * 上的软解绑与重新绑定，避免恢复流程与并发绑定竞态或插入重复行。
     */
    @Select("select * from project_repositories where project_id=#{projectId} and repository_id=#{repositoryId} for update")
    ProjectRepositoryEntity selectByProjectAndRepositoryForUpdate(@Param("projectId") UUID projectId,
                                                                  @Param("repositoryId") UUID repositoryId);

    /**
     * 统计项目当前生效（ACTIVE）的仓库绑定数，用作项目卡片的 repositoryCount，
     * 避免前端逐卡片 N+1 查询。软解绑（UNBOUND）的仓库不计入。
     */
    @Select("select count(*) from project_repositories where project_id=#{projectId} and status='ACTIVE'")
    Long countActiveByProject(@Param("projectId") UUID projectId);
}
