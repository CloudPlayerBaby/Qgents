package qg.qgent.service;

import org.springframework.stereotype.Service;
import qg.qgent.dto.TaskStatusRepositoryMapping;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskStepRepositoryEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskStepRepositoryMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 组装 TASK_STATUS 卡片需要的真实仓库上下文。
 * <p>
 * 仓库事实来自持久化 Workspace/TaskStep 数据，不读取 Planner 文本，也不调用 Worker 或 GitHub
 * 外部接口。查询失败由调用方降级为空映射，不能阻塞任务执行。
 */
@Service
public class TaskStatusRepositoryContextService {
    private final WorkspaceRepositoryMapper workspaceRepositories;
    private final TaskStepRepositoryMapper stepRepositories;
    private final ProjectRepositoryMapper projectRepositories;
    private final GitHubRepositoryMapper githubRepositories;

    public TaskStatusRepositoryContextService(WorkspaceRepositoryMapper workspaceRepositories,
                                              TaskStepRepositoryMapper stepRepositories,
                                              ProjectRepositoryMapper projectRepositories,
                                              GitHubRepositoryMapper githubRepositories) {
        this.workspaceRepositories = workspaceRepositories;
        this.stepRepositories = stepRepositories;
        this.projectRepositories = projectRepositories;
        this.githubRepositories = githubRepositories;
    }

    /** 返回 Task Workspace 中按 workspacePath 稳定排序的仓库映射。 */
    public List<TaskStatusRepositoryMapping> allRepositories(TaskEntity task) {
        if (task == null || task.getWorkspaceId() == null) {
            return List.of();
        }
        List<WorkspaceRepositoryEntity> worktrees = workspaceRepositories.selectByWorkspace(task.getWorkspaceId());
        if (worktrees == null || worktrees.isEmpty()) {
            return List.of();
        }
        Set<UUID> bindingIds = worktrees.stream()
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (bindingIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, ProjectRepositoryEntity> bindings = projectRepositories.selectBatchIds(bindingIds).stream()
                .filter(binding -> task.getProjectId().equals(binding.getProjectId()))
                .collect(Collectors.toMap(ProjectRepositoryEntity::getId, Function.identity(), (first, ignored) -> first));
        Set<UUID> githubIds = bindings.values().stream()
                .map(ProjectRepositoryEntity::getRepositoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, GitHubRepositoryEntity> remotes = githubIds.isEmpty() ? Map.of() : githubRepositories.selectBatchIds(githubIds).stream()
                .collect(Collectors.toMap(GitHubRepositoryEntity::getId, Function.identity(), (first, ignored) -> first));

        return worktrees.stream()
                .filter(worktree -> bindings.containsKey(worktree.getProjectRepositoryId()))
                .map(worktree -> mapping(worktree, bindings.get(worktree.getProjectRepositoryId()), remotes))
                .sorted(Comparator.comparing(TaskStatusRepositoryMapping::getWorkspacePath,
                        Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    /** 返回某个 TaskStep 实际声明的 Workspace 路径；没有 scope 时返回空列表。 */
    public List<String> currentPathsForStep(TaskEntity task, UUID taskStepId) {
        if (task == null || taskStepId == null) {
            return List.of();
        }
        List<TaskStepRepositoryEntity> scopes = stepRepositories.selectByStep(taskStepId);
        if (scopes == null || scopes.isEmpty()) {
            return List.of();
        }
        Set<UUID> repositoryIds = scopes.stream()
                .map(TaskStepRepositoryEntity::getProjectRepositoryId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return allRepositories(task).stream()
                .filter(mapping -> repositoryIds.contains(parseUuid(mapping.getRepositoryId())))
                .map(TaskStatusRepositoryMapping::getWorkspacePath)
                .filter(Objects::nonNull)
                .toList();
    }

    /** 返回指定项目仓库绑定对应的 Workspace 路径。 */
    public List<String> pathsForRepositories(TaskEntity task, Collection<UUID> repositoryIds) {
        if (repositoryIds == null || repositoryIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> ids = repositoryIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        return allRepositories(task).stream()
                .filter(mapping -> ids.contains(parseUuid(mapping.getRepositoryId())))
                .map(TaskStatusRepositoryMapping::getWorkspacePath)
                .filter(Objects::nonNull)
                .toList();
    }

    private TaskStatusRepositoryMapping mapping(WorkspaceRepositoryEntity worktree,
                                                ProjectRepositoryEntity binding,
                                                Map<UUID, GitHubRepositoryEntity> remotes) {
        GitHubRepositoryEntity remote = remotes.get(binding.getRepositoryId());
        String fullName = remote == null || blank(remote.getOwnerLogin()) || blank(remote.getName())
                ? null : remote.getOwnerLogin() + "/" + remote.getName();
        String name = blank(binding.getDisplayName())
                ? (remote == null ? null : remote.getName()) : binding.getDisplayName();
        return new TaskStatusRepositoryMapping(worktree.getWorkspacePath(),
                binding.getId().toString(), name, fullName, remote == null ? null : "GITHUB",
                worktree.getBaseRef(), worktree.getSourceBranch());
    }

    private UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
