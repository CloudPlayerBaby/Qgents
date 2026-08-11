package qg.qgent.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

@Mapper
public interface TeamMemberMapper {
    @Select("SELECT team_id, user_id, role FROM team_members WHERE user_id = #{userId}")
    @Results({
            @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "role", property = "role")
    })
    List<TeamMemberEntity> selectByUserId(UUID userId);

    @Select("SELECT team_id, user_id, role FROM team_members WHERE team_id = #{teamId} AND user_id = #{userId}")
    @Results({
            @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "user_id", property = "userId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "role", property = "role")
    })
    TeamMemberEntity selectByTeamAndUser(@Param("teamId") UUID teamId, @Param("userId") UUID userId);
}
