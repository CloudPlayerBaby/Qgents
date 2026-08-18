package qg.qgent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import qg.qgent.entity.WorkspaceEntity;

import java.util.UUID;

/**
 * Data access for persistent project workspaces.
 */
@Mapper
public interface WorkspaceMapper extends BaseMapper<WorkspaceEntity> {
    /**
     * Locks a workspace to serialize writers across continuation tasks.
     */
    @Select("select * from workspaces where id=#{workspaceId} for update")
    WorkspaceEntity selectByIdForUpdate(UUID workspaceId);

    /**
     * 以数据库 CAS 领取 Workspace 写入租约。过期租约可接管；未过期的其他 Task 租约不可覆盖。
     */
    @Update("update workspaces set write_lease_task_id=#{taskId}, write_lease_token=#{token}, "
            + "write_lease_expires_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 MINUTE), updated_at=UTC_TIMESTAMP(6) "
            + "where id=#{workspaceId} and project_id=#{projectId} and (write_lease_expires_at is null "
            + "or write_lease_expires_at <= UTC_TIMESTAMP(6) or (write_lease_task_id=#{taskId} "
            + "and write_lease_token=#{token}))")
    int claimWriteLease(@Param("projectId") UUID projectId, @Param("workspaceId") UUID workspaceId,
                        @Param("taskId") UUID taskId, @Param("token") String token);

    /**
     * 仅当前持有者可续租；返回 0 表示租约已被接管、过期或 Workspace 归属不匹配。
     */
    @Update("update workspaces set write_lease_expires_at=DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 30 MINUTE), "
            + "updated_at=UTC_TIMESTAMP(6) where id=#{workspaceId} and project_id=#{projectId} "
            + "and write_lease_task_id=#{taskId} and write_lease_token=#{token} "
            + "and write_lease_expires_at > UTC_TIMESTAMP(6)")
    int renewWriteLease(@Param("projectId") UUID projectId, @Param("workspaceId") UUID workspaceId,
                        @Param("taskId") UUID taskId, @Param("token") String token);

    /**
     * 仅当前令牌可释放租约，防止旧进程迟到的 finally 清理掉新持有者。
     */
    @Update("update workspaces set write_lease_task_id=null, write_lease_token=null, write_lease_expires_at=null, "
            + "updated_at=UTC_TIMESTAMP(6) where id=#{workspaceId} and project_id=#{projectId} "
            + "and write_lease_task_id=#{taskId} and write_lease_token=#{token}")
    int releaseWriteLease(@Param("projectId") UUID projectId, @Param("workspaceId") UUID workspaceId,
                          @Param("taskId") UUID taskId, @Param("token") String token);
}
