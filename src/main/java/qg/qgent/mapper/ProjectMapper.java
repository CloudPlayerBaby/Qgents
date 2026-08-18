package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.dto.ProjectMembershipView;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

@Mapper
public interface ProjectMapper extends BaseMapper<ProjectEntity> {
    @Select("SELECT id, team_id, created_by, name, description, status FROM projects WHERE id = #{projectId} FOR UPDATE")
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "created_by", property = "createdBy", typeHandler = UuidBinaryTypeHandler.class)
    })
    ProjectEntity selectByIdForUpdate(@Param("projectId") UUID projectId);

    @Select({"<script>",
            "SELECT p.id, p.team_id, p.name, p.description, p.status,",
            "CASE WHEN #{teamOwner} THEN 'PROJECT_ADMIN' ELSE pm.role END AS role",
            "FROM projects p LEFT JOIN project_members pm ON pm.project_id = p.id AND pm.user_id = #{userId}",
            "WHERE p.team_id = #{teamId}",
            "<if test='teamOwner == false'>AND pm.user_id IS NOT NULL</if>",
            "<if test='anchor != null'>AND p.id &gt; #{anchor}</if>",
            "ORDER BY p.id LIMIT #{limit}",
            "</script>"})
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class)
    })
    List<ProjectMembershipView> selectAccessiblePage(@Param("teamId") UUID teamId,
                                                     @Param("userId") UUID userId, @Param("teamOwner") boolean teamOwner,
                                                     @Param("anchor") UUID anchor, @Param("limit") int limit);

    @Select("SELECT id, team_id, created_by, name, description, status FROM projects "
            + "WHERE team_id = #{teamId} ORDER BY id FOR UPDATE")
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "created_by", property = "createdBy", typeHandler = UuidBinaryTypeHandler.class)
    })
    List<ProjectEntity> selectByTeamForUpdate(@Param("teamId") UUID teamId);

    @Select("SELECT DISTINCT p.id, p.team_id, p.name, p.description, p.status, "
            + "CASE WHEN t.owner_user_id = #{userId} AND tom.role = 'TEAM_OWNER' "
            + "THEN 'PROJECT_ADMIN' ELSE pm.role END AS role "
            + "FROM projects p INNER JOIN teams t ON t.id = p.team_id "
            + "LEFT JOIN team_members tom ON tom.team_id = t.id AND tom.user_id = #{userId} "
            + "LEFT JOIN project_members pm ON pm.project_id = p.id AND pm.user_id = #{userId} "
            + "WHERE p.status = 'ACTIVE' AND t.status = 'ACTIVE' "
            + "AND ((t.owner_user_id = #{userId} AND tom.role = 'TEAM_OWNER') "
            + "OR pm.user_id IS NOT NULL) ORDER BY p.id")
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class)
    })
    List<ProjectMembershipView> selectAccessibleByUser(@Param("userId") UUID userId);

    /**
     * 查询某团队下当前用户可见的项目（含最后活跃时间），按最后活跃倒序。
     * 最后活跃 = 该项目下所有群最近消息时间或创建时间的最大值；无群时以项目创建时间兜底。
     *
     * @param teamId    团队 ID
     * @param userId    当前用户 ID
     * @param teamOwner 当前用户是否为该团队 canonical Owner（Owner 可见团队全部项目）
     * @return 项目视图列表（含 lastActivityAt），按最后活跃倒序
     */
    @Select({"<script>",
            "SELECT p.id, p.team_id, p.name, p.description, p.status, ",
            "CASE WHEN #{teamOwner} THEN 'PROJECT_ADMIN' ELSE pm.role END AS role, ",
            "COALESCE((SELECT MAX(COALESCE(rg.last_message_at, rg.created_at)) ",
            "          FROM requirement_groups rg WHERE rg.project_id = p.id), p.created_at) AS last_activity_at ",
            "FROM projects p LEFT JOIN project_members pm ON pm.project_id = p.id AND pm.user_id = #{userId} ",
            "WHERE p.team_id = #{teamId} ",
            "<if test='teamOwner == false'>AND pm.user_id IS NOT NULL</if> ",
            "ORDER BY last_activity_at DESC, p.id",
            "</script>"})
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "last_activity_at", property = "lastActivityAt")
    })
    List<ProjectMembershipView> selectAccessibleByActivity(@Param("teamId") UUID teamId,
                                                           @Param("userId") UUID userId,
                                                           @Param("teamOwner") boolean teamOwner);
}
