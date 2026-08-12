package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Task 视图：包含其唯一 Workspace 与仓库范围摘要。
 * workspaceStatus 反映 Workspace 生命周期状态（PROVISIONING/READY/LEASED/ARCHIVED/FAILED），
 * repositories 给出任务实际代码操作所基于的每个 worktree 分支与提交事实，
 * repositoryIds 为兼容字段保留的仓库绑定 ID 列表。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskResponse {
    private String id;
    private String projectId;
    private String requirementGroupId;
    private String triggerMessageId;
    private String title;
    private String requirement;
    private String status;
    private String workspaceId;
    private String workspaceStatus;
    private String continuationOfTaskId;
    private List<String> repositoryIds;
    private List<TaskRepositoryScopeResponse> repositories;
    private String createdBy;
    private String createdAt;
    private String updatedAt;
}
