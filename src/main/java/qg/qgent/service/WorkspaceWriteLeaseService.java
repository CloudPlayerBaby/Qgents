package qg.qgent.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.mapper.WorkspaceMapper;

import java.util.UUID;

/**
 * Workspace 跨 JVM 的写入租约。
 * <p>
 * 该服务只执行短数据库 CAS，不在事务内调用 Worker、GitHub 或 LLM。调用方在任何会改动
 * Workspace 的外部操作前领取并定期续租，完成后按同一令牌释放。到期接管只用于进程崩溃恢复；
 * 正常执行器在每次 Worker 工具调用前都会续租，因此不会把两个正常 Task 同时放进一个 Workspace。
 */
@Service
public class WorkspaceWriteLeaseService {
    private final WorkspaceMapper workspaces;

    public WorkspaceWriteLeaseService(WorkspaceMapper workspaces) {
        this.workspaces = workspaces;
    }

    /**
     * 领取一把新的写入租约。若另一个 Task 正在写入，返回稳定冲突码而不修改对方状态。
     */
    public WorkspaceWriteLease acquire(UUID projectId, UUID workspaceId, UUID taskId) {
        if (projectId == null || workspaceId == null || taskId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_WRITE_LEASE_CONTEXT_INVALID",
                    "Workspace write lease context is incomplete");
        }
        String token = UUID.randomUUID().toString();
        if (workspaces.claimWriteLease(projectId, workspaceId, taskId, token) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_WRITE_LEASE_HELD",
                    "Workspace is currently being modified by another Task");
        }
        return new WorkspaceWriteLease(projectId, workspaceId, taskId, token);
    }

    /**
     * 在实际 Worker 写入前续租。租约已丢失时立即阻断外部写入，不能继续使用过期执行现场。
     */
    public void renew(WorkspaceWriteLease lease) {
        if (lease == null || workspaces.renewWriteLease(lease.getProjectId(), lease.getWorkspaceId(),
                lease.getTaskId(), lease.token()) != 1) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_WRITE_LEASE_LOST",
                    "Workspace write lease is no longer active");
        }
    }

    /**
     * 释放当前租约。令牌已失效说明已有恢复执行器接管，保持其状态即可。
     */
    public void release(WorkspaceWriteLease lease) {
        if (lease == null) {
            return;
        }
        workspaces.releaseWriteLease(lease.getProjectId(), lease.getWorkspaceId(), lease.getTaskId(), lease.token());
    }
}
