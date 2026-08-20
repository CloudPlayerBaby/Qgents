package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.entity.GitHubRepositoryEntity;
import qg.qgent.entity.ProjectRepositoryEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskStepRepositoryEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.GitHubRepositoryMapper;
import qg.qgent.mapper.ProjectRepositoryMapper;
import qg.qgent.mapper.TaskStepRepositoryMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskStatusRepositoryContextServiceTest {
    @Test
    void mapsWorkspacePathToRepositoryAndStepScope() {
        WorkspaceRepositoryMapper workspaces = mock(WorkspaceRepositoryMapper.class);
        TaskStepRepositoryMapper steps = mock(TaskStepRepositoryMapper.class);
        ProjectRepositoryMapper projects = mock(ProjectRepositoryMapper.class);
        GitHubRepositoryMapper github = mock(GitHubRepositoryMapper.class);
        TaskStatusRepositoryContextService service = new TaskStatusRepositoryContextService(
                workspaces, steps, projects, github);

        UUID projectId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        UUID githubId = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(UUID.randomUUID());
        task.setProjectId(projectId);
        task.setWorkspaceId(workspaceId);

        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(workspaceId);
        worktree.setProjectRepositoryId(bindingId);
        worktree.setWorkspacePath("repo-2");
        worktree.setBaseRef("main");
        worktree.setSourceBranch("feat/task");
        ProjectRepositoryEntity binding = new ProjectRepositoryEntity();
        binding.setId(bindingId);
        binding.setProjectId(projectId);
        binding.setRepositoryId(githubId);
        binding.setDisplayName("frontend");
        GitHubRepositoryEntity remote = new GitHubRepositoryEntity();
        remote.setId(githubId);
        remote.setOwnerLogin("Choco-emmm");
        remote.setName("testtesttest");
        when(workspaces.selectByWorkspace(workspaceId)).thenReturn(List.of(worktree));
        when(projects.selectBatchIds(anyCollection())).thenReturn(List.of(binding));
        when(github.selectBatchIds(anyCollection())).thenReturn(List.of(remote));
        TaskStepRepositoryEntity scope = new TaskStepRepositoryEntity();
        scope.setTaskStepId(stepId);
        scope.setProjectRepositoryId(bindingId);
        when(steps.selectByStep(stepId)).thenReturn(List.of(scope));

        assertEquals("Choco-emmm/testtesttest", service.allRepositories(task).get(0).getFullName());
        assertEquals("repo-2", service.currentPathsForStep(task, stepId).get(0));
    }
}
