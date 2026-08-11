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
    @Select("SELECT id, owner_user_id, name, status FROM teams WHERE id = #{teamId} FOR UPDATE")
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "owner_user_id", property = "ownerUserId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "name", property = "name"),
            @Result(column = "status", property = "status")
    })
    TeamEntity selectByIdForUpdate(UUID teamId);

    @Select({ "<script>",
            "SELECT t.id, t.owner_user_id, t.name, tm.role FROM team_members tm INNER JOIN teams t ON t.id = tm.team_id",
            "WHERE tm.user_id = #{userId}",
            "<if test='anchor != null'>AND t.id &gt; #{anchor}</if>",
            "ORDER BY t.id LIMIT #{limit}",
            "</script>" })
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "owner_user_id", property = "ownerUserId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "name", property = "name"),
            @Result(column = "role", property = "role")
    })
    List<TeamMembershipView> selectMembershipPage(@Param("userId") UUID userId,
            @Param("anchor") UUID anchor, @Param("limit") int limit);
}
