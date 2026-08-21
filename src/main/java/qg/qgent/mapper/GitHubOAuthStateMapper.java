package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import qg.qgent.entity.GitHubOAuthStateEntity;

import java.time.LocalDateTime;
import java.util.UUID;

@Mapper
public interface GitHubOAuthStateMapper extends BaseMapper<GitHubOAuthStateEntity> {
    @Update("UPDATE github_oauth_states SET consumed_at = #{consumedAt} "
            + "WHERE id = #{id} AND consumed_at IS NULL AND expires_at > #{consumedAt}")
    int consume(@Param("id") UUID id, @Param("consumedAt") LocalDateTime consumedAt);
}
