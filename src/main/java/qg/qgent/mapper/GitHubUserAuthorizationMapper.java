package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import qg.qgent.entity.GitHubUserAuthorizationEntity;

import java.time.LocalDateTime;
import java.util.UUID;

/** GitHub 用户 OAuth 授权记录访问；状态抢占与失效回写使用条件 UPDATE 保证并发安全。 */
@Mapper
public interface GitHubUserAuthorizationMapper extends BaseMapper<GitHubUserAuthorizationEntity> {
    /**
     * 撤销开始前原子抢占：仅允许从 ACTIVE/ERROR 转为 REVOKING，返回受影响行数。
     * 抢占成功后 requirePersonalCredential 因非 ACTIVE 而拒绝建仓，消除撤销与建仓并发竞态。
     */
    @Update("UPDATE github_user_authorizations SET status = 'REVOKING', updated_at = #{now} "
            + "WHERE user_id = #{userId} AND provider = 'GITHUB' AND status IN ('ACTIVE', 'ERROR')")
    int claimRevoking(@Param("userId") UUID userId, @Param("now") LocalDateTime now);

    /**
     * GitHub 远端返回 401 时回写本地失效：仅影响 ACTIVE 记录，置为 EXPIRED 并清除密文。
     * 返回 0 表示记录已非 ACTIVE（已撤销或已重新授权），调用方可忽略。
     */
    @Update("UPDATE github_user_authorizations SET status = 'EXPIRED', last_error_code = #{code}, "
            + "access_token_ciphertext = NULL, revoked_at = #{now}, updated_at = #{now} "
            + "WHERE user_id = #{userId} AND provider = 'GITHUB' AND status = 'ACTIVE'")
    int markInvalid(@Param("userId") UUID userId, @Param("code") String code, @Param("now") LocalDateTime now);

    /**
     * 撤销成功终态：置 REVOKED、清除密文与错误码。必须用显式 UPDATE 而不能用 updateById，
     * 因为 MyBatis-Plus 默认更新策略会忽略 null 字段，导致密文无法真正清空。
     */
    @Update("UPDATE github_user_authorizations SET status = 'REVOKED', access_token_ciphertext = NULL, "
            + "last_error_code = NULL, revoked_at = #{now}, updated_at = #{now} WHERE id = #{id}")
    int markRevoked(@Param("id") UUID id, @Param("now") LocalDateTime now);

    /**
     * 撤销失败终态：置 ERROR 并记录稳定错误码，保留密文供后续撤销重试。
     */
    @Update("UPDATE github_user_authorizations SET status = 'ERROR', last_error_code = #{code}, "
            + "revoked_at = #{now}, updated_at = #{now} WHERE id = #{id}")
    int markRevokeFailed(@Param("id") UUID id, @Param("code") String code, @Param("now") LocalDateTime now);
}
