package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.RequirementGroupEntity;

import java.util.List;
import java.util.UUID;

@Mapper
public interface RequirementGroupMapper extends BaseMapper<RequirementGroupEntity> {

    /**
     * 项目全部群（含主群与已归档），按最近活跃排序；从未发言的群以创建时间兜底。
     */
    @Select("select * from requirement_groups where project_id=#{projectId}"
            + " order by coalesce(last_message_at, created_at) desc")
    List<RequirementGroupEntity> listByProject(@Param("projectId") UUID projectId);

    /**
     * 查询当前用户在项目内可见的群：主群对所有项目成员可见，需求群仅创建者或显式成员可见。
     *
     * @param projectId 项目 ID
     * @param userId 当前用户 ID
     * @return 可见群列表，按最近活跃排序
     */
    @Select("SELECT rg.* FROM requirement_groups rg "
            + "WHERE rg.project_id = #{projectId} "
            + "AND (rg.group_type = 'PROJECT_MAIN' "
            + "OR rg.created_by = #{userId} "
            + "OR EXISTS (SELECT 1 FROM group_members gm "
            + "           WHERE gm.requirement_group_id = rg.id AND gm.user_id = #{userId})) "
            + "ORDER BY COALESCE(rg.last_message_at, rg.created_at) DESC")
    List<RequirementGroupEntity> listVisibleByProject(@Param("projectId") UUID projectId,
                                                       @Param("userId") UUID userId);

    /**
     * 批量取多个项目的主群（PROJECT_MAIN），供群聊工作台聚合（消除三层 N+1）。
     * 返回这些项目的主群实体，按最近活跃倒序。
     *
     * @param projectIds 项目 ID 列表
     * @return 这些项目的主群实体
     */
    @Select({"<script>",
            "SELECT * FROM requirement_groups WHERE group_type = 'PROJECT_MAIN' ",
            "AND project_id IN "
            + "(<foreach collection='projectIds' item='pid' separator=','>#{pid}</foreach>) ",
            "ORDER BY coalesce(last_message_at, created_at) DESC",
            "</script>"})
    List<RequirementGroupEntity> selectMainGroupsByProjectIds(@Param("projectIds") List<UUID> projectIds);
}
