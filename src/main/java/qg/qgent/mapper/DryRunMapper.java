package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.entity.DryRunEntity;

@Mapper
public interface DryRunMapper extends BaseMapper<DryRunEntity> {
    @Select("select * from dry_runs where id=#{id} for update")
    DryRunEntity selectByIdForUpdate(@Param("id") java.util.UUID id);

    @Update("update dry_runs set status='RUNNING',started_at=coalesce(started_at,UTC_TIMESTAMP(6)),"
            + "finished_at=null,claim_token=#{token},lease_expires_at=#{leaseExpiresAt},"
            + "updated_at=UTC_TIMESTAMP(6),attempt_count=attempt_count+1 where id=#{id} and "
            + "(status='QUEUED' or (status='RUNNING' and lease_expires_at < #{now}))")
    int claim(@Param("id") java.util.UUID id, @Param("token") String token,
              @Param("now") java.time.LocalDateTime now,
              @Param("leaseExpiresAt") java.time.LocalDateTime leaseExpiresAt);

    @Select("select id from dry_runs where status='QUEUED' or (status='RUNNING' and lease_expires_at < #{now}) "
            + "order by created_at limit #{limit}")
    java.util.List<java.util.UUID> selectRecoverable(@Param("now") java.time.LocalDateTime now,
                                                     @Param("limit") int limit);

    @Update("update dry_runs set status=#{status},report=#{report,typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler},"
            + "finished_at=UTC_TIMESTAMP(6),"
            + "head_commit=coalesce(#{headCommit},head_commit),claim_token=null,lease_expires_at=null,"
            + "active_claim_key=null,updated_at=UTC_TIMESTAMP(6) "
            + "where id=#{id} and claim_token=#{token}")
    int complete(@Param("id") java.util.UUID id, @Param("token") String token,
                 @Param("status") String status, @Param("report") java.util.Map<String, Object> report,
                 @Param("headCommit") String headCommit);

    /**
     * 解散团队时按项目逐层删除不再被任何运行引用的叶子运行。
     * <p>
     * dry_runs 存在自引用外键 {@code fk_dry_run_retry_source}（retry_of_dry_run_id -&gt; id）且无级联，
     * 重试血缘可多级；单条批量 DELETE 无法在同一语句内删除互相引用的父子行，须循环调用
     * 本方法直至返回 0。重试引用限定在相同项目内，保证逐层收敛。
     * <p>
     * MySQL 不允许在 DELETE 的 FROM/子查询中再次读取目标表（error 1093），因此把
     * 「被引用的父行 id」子查询用派生表 {@code AS tmp} 包一层，让 MySQL 先物化；
     * 同时用 {@code IS NOT NULL} 排除空值，避免 {@code NOT IN} 遇到 NULL 时整体失效。
     *
     * @param projectId 项目 ID
     * @return 本次实际删除的行数；为 0 表示已无满足条件的运行
     */
    @Delete("delete from dry_runs where project_id = #{projectId} "
            + "and id not in (select rid from (select retry_of_dry_run_id as rid from dry_runs "
            + "where project_id = #{projectId} and retry_of_dry_run_id is not null) as tmp)")
    int deleteUnreferencedDryRuns(@Param("projectId") java.util.UUID projectId);
}
