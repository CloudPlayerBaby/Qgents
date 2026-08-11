package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import qg.qgent.entity.TeamInvitationEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

@Mapper
public interface TeamInvitationMapper extends BaseMapper<TeamInvitationEntity> {
    @Select({ "<script>",
            "SELECT id, team_id, invited_by, email_normalized, token_hash, status, expires_at, accepted_at",
            "FROM team_invitations WHERE team_id = #{teamId}",
            "<if test='anchor != null'>AND id &gt; #{anchor}</if>",
            "ORDER BY id LIMIT #{limit}",
            "</script>" })
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "invited_by", property = "invitedBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "email_normalized", property = "emailNormalized"),
            @Result(column = "token_hash", property = "tokenHash"),
            @Result(column = "status", property = "status"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "accepted_at", property = "acceptedAt")
    })
    List<TeamInvitationEntity> selectInvitationPage(@Param("teamId") UUID teamId,
            @Param("anchor") UUID anchor, @Param("limit") int limit);
}
