package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import qg.qgent.dto.TeamMembershipView;
import qg.qgent.entity.TeamEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.UUID;
import java.util.List;

@Mapper
public interface TeamMapper extends BaseMapper<TeamEntity> {
    @Select("SELECT id, owner_user_id, name, description, status, created_at FROM teams WHERE id = #{teamId} FOR UPDATE")
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "owner_user_id", property = "ownerUserId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "name", property = "name"),
            @Result(column = "description", property = "description"),
            @Result(column = "status", property = "status"),
            @Result(column = "created_at", property = "createdAt")
    })
    TeamEntity selectByIdForUpdate(UUID teamId);

    @Select({ "<script>",
            "SELECT t.id, t.owner_user_id, t.name, t.description, t.created_at, tm.role,",
            "(SELECT COUNT(*) FROM team_members tc WHERE tc.team_id = t.id) AS member_count",
            "FROM team_members tm INNER JOIN teams t ON t.id = tm.team_id",
            "WHERE tm.user_id = #{userId}",
            "<if test='anchor != null'>AND t.id &gt; #{anchor}</if>",
            "ORDER BY t.id LIMIT #{limit}",
            "</script>" })
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "owner_user_id", property = "ownerUserId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "name", property = "name"),
            @Result(column = "description", property = "description"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "role", property = "role"),
            @Result(column = "member_count", property = "memberCount")
    })
    List<TeamMembershipView> selectMembershipPage(@Param("userId") UUID userId,
            @Param("anchor") UUID anchor, @Param("limit") int limit);
}
