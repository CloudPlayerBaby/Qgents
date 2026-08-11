package qg.qgent.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.ProjectMemberEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ProjectMemberMapper {
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
    ProjectMemberEntity selectByProjectAndUser(UUID projectId, UUID userId);

    /** 群成员行：项目成员 join 用户基础信息（群成员即项目成员）。 */
    @Data
    class Member {
        private UUID userId;
        private String displayName;
        private String avatarUrl;
    }

    /**
     * 查询项目成员列表（含昵称、头像），按加入时间排序。
     *
     * @param projectId 项目 ID
     * @return 成员基础信息列表
     */
    @Select("SELECT u.id AS user_id, u.display_name, u.avatar_url FROM project_members pm"
            + " JOIN users u ON u.id = pm.user_id WHERE pm.project_id = #{projectId} ORDER BY pm.joined_at")
    @Results({
            @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "avatar_url", property = "avatarUrl")
    })
    List<Member> selectMembers(UUID projectId);

    /**
     * 统计项目成员数（用作群详情 memberCount）。
     *
     * @param projectId 项目 ID
     * @return 成员总数
     */
    @Select("SELECT COUNT(*) FROM project_members WHERE project_id = #{projectId}")
    Long countMembers(UUID projectId);

    /**
     * 统计项目 Admin 数量，用于「最后一名 Project Admin 不可退出」约束。
     *
     * @param projectId 项目 ID
     * @return Project Admin 数量
     */
    @Select("SELECT COUNT(*) FROM project_members WHERE project_id = #{projectId} AND role = 'PROJECT_ADMIN'")
    Long countAdmins(UUID projectId);

    /**
     * 从项目移除指定成员（退出群聊即移出项目成员）。
     *
     * @param projectId 项目 ID
     * @param userId    要移除的用户 ID
     * @return 影响行数
     */
    @Delete("DELETE FROM project_members WHERE project_id = #{projectId} AND user_id = #{userId}")
    int deleteByProjectAndUser(UUID projectId, UUID userId);
}
