package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
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

    @Select({ "<script>",
            "SELECT p.id, p.team_id, p.name, p.description, p.status,",
            "CASE WHEN #{teamOwner} THEN 'PROJECT_ADMIN' ELSE pm.role END AS role",
            "FROM projects p LEFT JOIN project_members pm ON pm.project_id = p.id AND pm.user_id = #{userId}",
            "WHERE p.team_id = #{teamId}",
            "<if test='teamOwner == false'>AND pm.user_id IS NOT NULL</if>",
            "<if test='anchor != null'>AND p.id &gt; #{anchor}</if>",
            "ORDER BY p.id LIMIT #{limit}",
            "</script>" })
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
}
