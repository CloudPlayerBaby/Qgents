package qg.qgent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

@Mapper
public interface TeamMemberMapper {
        @Select("SELECT team_id, user_id, role FROM team_members WHERE team_id = #{teamId} AND user_id = #{userId}")
        @Results({ @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
                        @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
                        @Result(column = "role", property = "role") })
        TeamMemberEntity selectByTeamAndUser(@Param("teamId") UUID teamId, @Param("userId") UUID userId);

        @Select("SELECT team_id, user_id, role FROM team_members WHERE team_id = #{teamId} ORDER BY user_id")
        @Results({ @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
                        @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
                        @Result(column = "role", property = "role") })
        List<TeamMemberEntity> selectByTeamId(@Param("teamId") UUID teamId);

        @Select({ "<script>",
                        "SELECT team_id, user_id, role FROM team_members WHERE team_id = #{teamId}",
                        "<if test='anchor != null'>AND user_id &gt; #{anchor}</if>",
                        "ORDER BY user_id LIMIT #{limit}",
                        "</script>" })
        @Results({ @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
                        @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
                        @Result(column = "role", property = "role") })
        List<TeamMemberEntity> selectMemberPage(@Param("teamId") UUID teamId, @Param("anchor") UUID anchor,
                        @Param("limit") int limit);

        @Select("SELECT team_id, user_id, role FROM team_members WHERE team_id = #{teamId} AND role = 'TEAM_OWNER' ORDER BY user_id FOR UPDATE")
        @Results({ @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
                        @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
                        @Result(column = "role", property = "role") })
        List<TeamMemberEntity> selectOwnersForUpdate(@Param("teamId") UUID teamId);

        @Insert("INSERT INTO team_members (team_id, user_id, role) VALUES (#{teamId}, #{userId}, #{role})")
        int insert(TeamMemberEntity member);

        @Update("UPDATE team_members SET role = #{role} WHERE team_id = #{teamId} AND user_id = #{userId}")
        int updateRole(@Param("teamId") UUID teamId, @Param("userId") UUID userId, @Param("role") String role);

        @Delete("DELETE FROM team_members WHERE team_id = #{teamId} AND user_id = #{userId}")
        int deleteByTeamAndUser(@Param("teamId") UUID teamId, @Param("userId") UUID userId);

        @Select("SELECT team_id, user_id, role FROM team_members WHERE user_id = #{userId}")
        @Results({
                        @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
                        @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
                        @Result(column = "role", property = "role")
        })
        List<TeamMemberEntity> selectByUserId(UUID userId);
}
