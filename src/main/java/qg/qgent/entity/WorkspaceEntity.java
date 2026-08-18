package qg.qgent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persistent project-scoped development workspace; repository worktrees live below this root.
 */
@Data
@TableName("workspaces")
public class WorkspaceEntity {
    /**
     * UUIDv7 workspace identifier.
     */
    @TableId(type = IdType.INPUT)
    private UUID id;
    /**
     * Owning project isolation boundary.
     */
    private UUID projectId;
    /**
     * Opaque storage key; never exposes a host path to clients.
     */
    private String storageKey;
    /**
     * Lifecycle state: PROVISIONING/READY/LEASED/ARCHIVED/FAILED.
     */
    private String status;
    /**
     * 当前持有 Workspace 写入租约的 Task。仅用于跨后端实例互斥，不代表 Workspace 的归属关系。
     */
    private UUID writeLeaseTaskId;
    /**
     * 租约随机令牌；释放与续租必须同时匹配 Task 和令牌，避免过期执行器释放后来者的租约。
     */
    private String writeLeaseToken;
    /**
     * 写入租约过期时间（UTC）。进程崩溃后允许新的执行器接管，但正常执行会在每次 Worker 工具调用前续租。
     */
    private LocalDateTime writeLeaseExpiresAt;
    /**
     * UTC creation time.
     */
    private LocalDateTime createdAt;
    /**
     * UTC last-update time.
     */
    private LocalDateTime updatedAt;
}
