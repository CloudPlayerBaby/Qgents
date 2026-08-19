package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.entity.TestRunEntity;

@Mapper
public interface TestRunMapper extends BaseMapper<TestRunEntity> {
    /**
     * 多实例下原子领取一个待执行或租约已过期的运行。
     */
    @Update("update test_runs set status='RUNNING',started_at=coalesce(started_at,UTC_TIMESTAMP(6)),"
            + "finished_at=null,claim_token=#{token},lease_expires_at=#{leaseExpiresAt},"
            + "attempt_count=attempt_count+1 where id=#{id} and "
            + "(status='QUEUED' or (status='RUNNING' and lease_expires_at < #{now}))")
    int claim(@Param("id") java.util.UUID id, @Param("token") String token,
              @Param("now") java.time.LocalDateTime now,
              @Param("leaseExpiresAt") java.time.LocalDateTime leaseExpiresAt);

    @Select("select id from test_runs where status='QUEUED' or (status='RUNNING' and lease_expires_at < #{now}) "
            + "order by created_at limit #{limit}")
    java.util.List<java.util.UUID> selectRecoverable(@Param("now") java.time.LocalDateTime now,
                                                     @Param("limit") int limit);

    @Update("update test_runs set status=#{status},summary=#{summary,typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler},"
            + "finished_at=UTC_TIMESTAMP(6),"
            + "claim_token=null,lease_expires_at=null where id=#{id} and claim_token=#{token}")
    int complete(@Param("id") java.util.UUID id, @Param("token") String token,
                 @Param("status") String status, @Param("summary") java.util.Map<String, Object> summary);

    @Select("select id from test_runs where status in ('PASSED','FAILED','CANCELLED') "
            + "and execution_workspace_id is not null order by updated_at limit #{limit}")
    java.util.List<java.util.UUID> selectCleanupPending(@Param("limit") int limit);

    @Update("update test_runs set execution_workspace_id=null where id=#{id} and execution_workspace_id=#{workspaceId}")
    int clearExecutionWorkspace(@Param("id") java.util.UUID id, @Param("workspaceId") java.util.UUID workspaceId);
}
