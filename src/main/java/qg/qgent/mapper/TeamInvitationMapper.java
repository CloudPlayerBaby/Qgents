package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import qg.qgent.entity.TeamInvitationEntity;
import qg.qgent.handler.UuidBinaryTypeHandler;

import java.util.List;
import java.util.UUID;

@Mapper
public interface TeamInvitationMapper extends BaseMapper<TeamInvitationEntity> {
    @Select({"<script>",
            "SELECT id, team_id, invited_by, email_normalized, token_hash, status, expires_at, accepted_at, created_at",
            "FROM team_invitations WHERE team_id = #{teamId}",
            "<if test='anchor != null'>AND id &gt; #{anchor}</if>",
            "ORDER BY id LIMIT #{limit}",
            "</script>"})
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "invited_by", property = "invitedBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "email_normalized", property = "emailNormalized"),
            @Result(column = "token_hash", property = "tokenHash"),
            @Result(column = "status", property = "status"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "accepted_at", property = "acceptedAt"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<TeamInvitationEntity> selectInvitationPage(@Param("teamId") UUID teamId,
                                                    @Param("anchor") UUID anchor, @Param("limit") int limit);

    /**
     * 按被邀请邮箱分页查询待处理（PENDING）邀请，按 id 升序，用于收件人视角列表。
     * 过期判定由服务层在映射响应时完成（PENDING 且已过期按 EXPIRED 展示），
     * 因此查询层只按 PENDING 过滤，避免遗漏刚过期的记录。
     */
    @Select({"<script>",
            "SELECT id, team_id, invited_by, email_normalized, token_hash, status, expires_at, accepted_at, created_at",
            "FROM team_invitations WHERE email_normalized = #{emailNormalized} AND status = 'PENDING'",
            "<if test='anchor != null'>AND id &gt; #{anchor}</if>",
            "ORDER BY id LIMIT #{limit}",
            "</script>"})
    @Results({
            @Result(column = "id", property = "id", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "team_id", property = "teamId", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "invited_by", property = "invitedBy", typeHandler = UuidBinaryTypeHandler.class),
            @Result(column = "email_normalized", property = "emailNormalized"),
            @Result(column = "token_hash", property = "tokenHash"),
            @Result(column = "status", property = "status"),
            @Result(column = "expires_at", property = "expiresAt"),
            @Result(column = "accepted_at", property = "acceptedAt"),
            @Result(column = "created_at", property = "createdAt")
    })
    List<TeamInvitationEntity> selectPendingByEmail(@Param("emailNormalized") String emailNormalized,
                                                    @Param("anchor") UUID anchor, @Param("limit") int limit);
}
