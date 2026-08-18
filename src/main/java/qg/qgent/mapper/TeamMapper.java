package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.dto.TeamMembershipView;
import qg.qgent.entity.TeamEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

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

    @Select({"<script>",
            "SELECT t.id, t.owner_user_id, t.name, t.description, t.created_at, tm.role,",
            "(SELECT COUNT(*) FROM team_members tc WHERE tc.team_id = t.id) AS member_count",
            "FROM team_members tm INNER JOIN teams t ON t.id = tm.team_id",
            "WHERE tm.user_id = #{userId}",
            "<if test='anchor != null'>AND t.id &gt; #{anchor}</if>",
            "ORDER BY t.id LIMIT #{limit}",
            "</script>"})
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

    /**
     * 查询当前用户加入的团队（含最后活跃时间），按最后活跃倒序。
     * 最后活跃 = 该团队下所有项目最后活跃的最大值（项目最后活跃 = 其下群最近消息时间的最大值）；
     * 旗下任何项目都无消息时为 null（沉底），不兜底创建时间——避免新创建的无活跃团队被
     * 「伪造」成最新活跃而排首位。
     *
     * @param userId 当前用户 ID
     * @return 团队视图列表（含 lastActivityAt），按最后活跃倒序
     */
    @Select("SELECT t.id, t.owner_user_id, t.name, t.description, t.created_at, tm.role, "
            + "(SELECT COUNT(*) FROM team_members tc WHERE tc.team_id = t.id) AS member_count, "
            + "(SELECT MAX(p_act) FROM ( "
            + "  SELECT (SELECT MAX(rg.last_message_at) FROM requirement_groups rg "
            + "          WHERE rg.project_id = p.id) AS p_act "
            + "  FROM projects p WHERE p.team_id = t.id) x) AS last_activity_at "
            + "FROM team_members tm INNER JOIN teams t ON t.id = tm.team_id "
            + "WHERE tm.user_id = #{userId} "
            + "ORDER BY last_activity_at DESC, t.id")
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "owner_user_id", property = "ownerUserId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "name", property = "name"),
            @Result(column = "description", property = "description"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "role", property = "role"),
            @Result(column = "member_count", property = "memberCount"),
            @Result(column = "last_activity_at", property = "lastActivityAt")
    })
    List<TeamMembershipView> selectMembershipByActivity(@Param("userId") UUID userId);
}
