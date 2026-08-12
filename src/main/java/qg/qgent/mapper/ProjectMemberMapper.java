package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.dto.GroupMemberRow;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMemberEntity> {
    @Delete("DELETE pm FROM project_members pm INNER JOIN projects p ON p.id = pm.project_id WHERE p.team_id = #{teamId} AND pm.user_id = #{userId}")
    int deleteByTeamAndUser(@Param("teamId") UUID teamId, @Param("userId") UUID userId);
    @Select("SELECT project_id, user_id, role FROM project_members WHERE user_id = #{userId}")
    @Results({
            @Result(column = "project_id", property = "projectId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "role", property = "role")
    })
    List<ProjectMemberEntity> selectByUserId(UUID userId);

    @Select("SELECT project_id, user_id, role FROM project_members WHERE project_id = #{projectId} AND user_id = #{userId}")
    @Results({
            @Result(column = "project_id", property = "projectId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "role", property = "role")
    })
    ProjectMemberEntity selectByProjectAndUser(@Param("projectId") UUID projectId, @Param("userId") UUID userId);

    @Select({ "<script>",
            "SELECT project_id, user_id, role FROM project_members WHERE project_id = #{projectId}",
            "<if test='anchor != null'>AND user_id &gt; #{anchor}</if>",
            "ORDER BY user_id LIMIT #{limit}",
            "</script>" })
    @Results({
            @Result(column = "project_id", property = "projectId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class)
    })
    List<ProjectMemberEntity> selectMemberPage(@Param("projectId") UUID projectId,
            @Param("anchor") UUID anchor, @Param("limit") int limit);

    @Update("UPDATE project_members SET role = #{role} WHERE project_id = #{projectId} AND user_id = #{userId}")
    int updateRole(@Param("projectId") UUID projectId, @Param("userId") UUID userId,
            @Param("role") String role);

    @Delete("DELETE FROM project_members WHERE project_id = #{projectId} AND user_id = #{userId}")
    int deleteByProjectAndUser(@Param("projectId") UUID projectId, @Param("userId") UUID userId);

    @Select("SELECT COUNT(*) FROM project_members WHERE project_id = #{projectId} AND role = 'PROJECT_ADMIN'")
    int countAdmins(@Param("projectId") UUID projectId);

    /** 查询项目成员列表（含昵称、头像），按加入时间排序（群成员即项目成员）。 */
    @Select("SELECT u.id AS user_id, u.display_name, u.avatar_url FROM project_members pm"
            + " JOIN users u ON u.id = pm.user_id WHERE pm.project_id = #{projectId} ORDER BY pm.joined_at")
    @Results({
            @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "avatar_url", property = "avatarUrl")
    })
    List<GroupMemberRow> selectMembers(@Param("projectId") UUID projectId);

    /** 统计项目成员数（用作群详情 memberCount）。 */
    @Select("SELECT COUNT(*) FROM project_members WHERE project_id = #{projectId}")
    Long countMembers(@Param("projectId") UUID projectId);
}
