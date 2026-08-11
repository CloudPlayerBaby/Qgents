package qg.qgent.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * 需求群与项目仓库关联数据访问（复合主键，使用自定义 SQL）。
 */
@Mapper
public interface RequirementGroupRepositoryMapper {

    /**
     * 查询群关联的项目仓库绑定 ID 列表。
     *
     * @param groupId 需求群 ID
     * @return 项目仓库绑定 ID 列表
     */
    @Select("select project_repository_id from requirement_group_repositories where requirement_group_id=#{groupId}")
    List<UUID> selectRepositoryIds(UUID groupId);

    /**
     * 新增一条群与仓库关联；重复关联由复合主键去重。
     *
     * @param groupId        需求群 ID
     * @param repositoryId   项目仓库绑定 ID
     */
    @Insert("insert into requirement_group_repositories(requirement_group_id, project_repository_id)"
            + " values(#{groupId}, #{repositoryId})")
    int insertLink(@Param("groupId") UUID groupId, @Param("repositoryId") UUID repositoryId);

    /**
     * 清空群的全部仓库关联（用于整体替换）。
     *
     * @param groupId 需求群 ID
     */
    @Delete("delete from requirement_group_repositories where requirement_group_id=#{groupId}")
    int deleteByGroup(UUID groupId);

    /**
     * 判断项目仓库绑定是否属于指定项目（关联仓库必须已绑定到该项目）。
     *
     * @param projectId    项目 ID
     * @param repositoryId 项目仓库绑定 ID
     * @return 存在返回 1，否则返回 null
     */
    @Select("select 1 from project_repositories where id=#{repositoryId} and project_id=#{projectId}")
    Integer countRepositoryInProject(@Param("projectId") UUID projectId, @Param("repositoryId") UUID repositoryId);
}
