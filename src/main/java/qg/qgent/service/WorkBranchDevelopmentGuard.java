package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.entity.MergeRequestEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.MergeRequestMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 工作分支开发锁定门禁。
 *
 * <p>未合并的 MR（OPEN 或 CLOSED）都表示该 source branch 已经进入评审生命周期，
 * 只能等待真实 MERGED 后恢复开发。该服务只读取已持久化的 Workspace worktree 和 MR 镜像，
 * 不访问 GitHub，也不持有数据库锁调用外部服务。</p>
 */
@Service
public class WorkBranchDevelopmentGuard {
    private final WorkspaceRepositoryMapper worktrees;
    private final MergeRequestMapper mergeRequests;

    public WorkBranchDevelopmentGuard(WorkspaceRepositoryMapper worktrees, MergeRequestMapper mergeRequests) {
        this.worktrees = worktrees;
        this.mergeRequests = mergeRequests;
    }

    /**
     * 创建 continuation Task 前检查 Workspace 的所有仓库分支。
     */
    public void requireContinuationAllowed(UUID projectId, UUID workspaceId) {
        requireWorkspaceWritable(projectId, workspaceId, "WORKSPACE_CONTINUATION_BLOCKED_BY_OPEN_MR",
                "该工作分支存在未合并的 MR，合并前不能继续开发");
    }

    /**
     * Diff commit/push 前检查 Workspace 的所有仓库分支。
     */
    public void requireDiffDeliveryAllowed(UUID projectId, UUID workspaceId) {
        requireWorkspaceWritable(projectId, workspaceId, "DIFF_DELIVERY_BLOCKED_BY_OPEN_MR",
                "当前工作分支存在未合并的 MR，不能继续进行 Diff 交付");
    }

    /**
     * Worker 创建或继续使用可写 Sandbox 前的最终门禁。
     */
    public void requireWorkerWriteAllowed(UUID projectId, UUID workspaceId) {
        requireWorkspaceWritable(projectId, workspaceId, "WORKSPACE_WRITE_BLOCKED_BY_OPEN_MR",
                "当前工作分支存在未合并的 MR，不能继续写入 Workspace");
    }

    /**
     * 直接针对一个仓库分支检查，供 commit/push 等单仓库入口在外部调用前再次校验。
     */
    public void requireBranchWritable(UUID projectId, UUID repositoryId, String sourceBranch,
                                      String code, String message) {
        if (repositoryId == null || sourceBranch == null || sourceBranch.isBlank()) {
            return;
        }
        MergeRequestEntity blocker = blockingMr(repositoryId, sourceBranch);
        if (blocker == null) {
            return;
        }
        throw blocked(code, message, List.of(detail(blocker)));
    }

    private void requireWorkspaceWritable(UUID projectId, UUID workspaceId, String code, String message) {
        if (projectId == null || workspaceId == null) {
            throw new ApiException(HttpStatus.CONFLICT, "WORKSPACE_CONTINUATION_INVALID",
                    "Workspace 写入上下文不完整");
        }
        List<WorkspaceRepositoryEntity> values = worktrees.selectByWorkspace(workspaceId);
        if (values == null) {
            return;
        }
        List<Map<String, Object>> blockers = new ArrayList<>();
        for (WorkspaceRepositoryEntity worktree : values) {
            MergeRequestEntity blocker = blockingMr(worktree.getProjectRepositoryId(), worktree.getSourceBranch());
            if (blocker != null) {
                blockers.add(detail(blocker));
            }
        }
        if (!blockers.isEmpty()) {
            throw blocked(code, message, blockers);
        }
    }

    private MergeRequestEntity blockingMr(UUID repositoryId, String sourceBranch) {
        if (repositoryId == null || sourceBranch == null || sourceBranch.isBlank()) {
            return null;
        }
        return mergeRequests.selectOne(Wrappers.<MergeRequestEntity>lambdaQuery()
                .eq(MergeRequestEntity::getProjectRepositoryId, repositoryId)
                .eq(MergeRequestEntity::getSourceBranch, sourceBranch)
                .ne(MergeRequestEntity::getStatus, "MERGED")
                .orderByDesc(MergeRequestEntity::getProviderUpdatedAt)
                .orderByDesc(MergeRequestEntity::getCreatedAt)
                .last("LIMIT 1"));
    }

    private Map<String, Object> detail(MergeRequestEntity mr) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mergeRequestId", mr.getId());
        value.put("providerNumber", mr.getProviderNumber());
        value.put("status", mr.getStatus());
        value.put("projectRepositoryId", mr.getProjectRepositoryId());
        value.put("sourceBranch", mr.getSourceBranch());
        return value;
    }

    private ApiException blocked(String code, String message, List<?> details) {
        return new ApiException(HttpStatus.CONFLICT, code, message, details);
    }
}
