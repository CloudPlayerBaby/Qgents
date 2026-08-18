package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.entity.DryRunEntity;

@Mapper
public interface DryRunMapper extends BaseMapper<DryRunEntity> {
    @Update("update dry_runs set status='RUNNING',claim_token=#{token},lease_expires_at=#{leaseExpiresAt},"
            + "attempt_count=attempt_count+1 where id=#{id} and "
            + "(status='QUEUED' or (status='RUNNING' and lease_expires_at < #{now}))")
    int claim(@Param("id") java.util.UUID id, @Param("token") String token,
              @Param("now") java.time.LocalDateTime now,
              @Param("leaseExpiresAt") java.time.LocalDateTime leaseExpiresAt);

    @Select("select id from dry_runs where status='QUEUED' or (status='RUNNING' and lease_expires_at < #{now}) "
            + "order by created_at limit #{limit}")
    java.util.List<java.util.UUID> selectRecoverable(@Param("now") java.time.LocalDateTime now,
                                                     @Param("limit") int limit);

    @Update("update dry_runs set status=#{status},report=#{report,typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler},"
            + "head_commit=coalesce(#{headCommit},head_commit),claim_token=null,lease_expires_at=null,"
            + "updated_at=UTC_TIMESTAMP(6) "
            + "where id=#{id} and claim_token=#{token}")
    int complete(@Param("id") java.util.UUID id, @Param("token") String token,
                 @Param("status") String status, @Param("report") java.util.Map<String, Object> report,
                 @Param("headCommit") String headCommit);
}
