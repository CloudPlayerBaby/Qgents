package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import qg.qgent.entity.GitCredentialGrant;

import java.time.LocalDateTime;

@Mapper
public interface GitCredentialGrantMapper extends BaseMapper<GitCredentialGrant> {
    
    /**
     * 尝试兑换授权记录：将其标记为已使用，利用行级锁保证只能被兑换一次。
     *
     * @param hash               Grant UUID 的 SHA-256 哈希值
     * @param expectedHeadCommit 预期的头指针
     * @param now                当前时间（用于检查是否过期）
     * @return 影响的行数，为1表示兑换成功，为0表示不存在、已使用或已过期、HEAD 不匹配
     */
    @Update("UPDATE git_credential_grants SET is_used = 1 " +
            "WHERE grant_id_hash = #{hash} " +
            "AND expected_head_commit = #{expectedHeadCommit} " +
            "AND is_used = 0 " +
            "AND expires_at > #{now}")
    int exchangeGrant(@Param("hash") String hash, 
                      @Param("expectedHeadCommit") String expectedHeadCommit, 
                      @Param("now") LocalDateTime now);
}
