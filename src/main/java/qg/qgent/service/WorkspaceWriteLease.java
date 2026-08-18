package qg.qgent.service;

import java.util.UUID;

/**
 * 一次已领取的 Workspace 写入租约。令牌只在服务端内存和数据库中流转，绝不进入 API、日志或 Agent 上下文。
 */
public final class WorkspaceWriteLease {
    private final UUID projectId;
    private final UUID workspaceId;
    private final UUID taskId;
    private final String token;

    WorkspaceWriteLease(UUID projectId, UUID workspaceId, UUID taskId, String token) {
        this.projectId = projectId;
        this.workspaceId = workspaceId;
        this.taskId = taskId;
        this.token = token;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    String token() {
        return token;
    }
}
