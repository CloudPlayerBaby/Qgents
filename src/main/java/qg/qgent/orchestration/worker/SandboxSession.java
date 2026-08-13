package qg.qgent.orchestration.worker;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一次 Task 编排期间持有的 Sandbox 会话（不可变值对象）。
 * <p>
 * 覆盖 Task 的 PLAN→CODING→TESTING→REVIEWING 全链路（含质量修复重试），
 * 由 {@link SandboxSessionManager} 在编排入口创建、在终态后销毁。端口实现据此
 * 把只携带 workspaceId 的调用解析为 worker 需要的 sandboxId 与 repositoryId。
 */
public final class SandboxSession {

    private final UUID taskId;
    private final UUID workspaceId;
    private final UUID sandboxId;
    private final String storageKey;
    /** 有序仓库编号，工具执行时据此选择工作目录。 */
    private final List<UUID> repositoryIds;
    /** workspacePath（一级相对目录名）到仓库编号的映射。 */
    private final Map<String, UUID> repositoryByPath;

    public SandboxSession(UUID taskId, UUID workspaceId, UUID sandboxId, String storageKey,
            List<UUID> repositoryIds, Map<String, UUID> repositoryByPath) {
        this.taskId = taskId;
        this.workspaceId = workspaceId;
        this.sandboxId = sandboxId;
        this.storageKey = storageKey;
        this.repositoryIds = repositoryIds;
        this.repositoryByPath = repositoryByPath;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getSandboxId() {
        return sandboxId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public List<UUID> getRepositoryIds() {
        return repositoryIds;
    }

    public Map<String, UUID> getRepositoryByPath() {
        return repositoryByPath;
    }

    /** 单仓库 Workspace 时返回唯一仓库编号，多仓库或无仓库返回 null。 */
    public UUID singleRepository() {
        return repositoryIds != null && repositoryIds.size() == 1 ? repositoryIds.get(0) : null;
    }
}
