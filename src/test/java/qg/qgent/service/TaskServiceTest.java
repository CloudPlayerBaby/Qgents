package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import qg.qgent.dto.GroupContext;
import qg.qgent.orchestration.TaskContextSnapshotCodec;
import qg.qgent.api.ApiException;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Security and persistence behavior tests for the confirmed Task workflow model. */
class TaskServiceTest {
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final WorkspaceRepositoryMapper repositories = mock(WorkspaceRepositoryMapper.class);
    private final TaskStepMapper steps = mock(TaskStepMapper.class);
    private final TaskStepDependencyMapper dependencies = mock(TaskStepDependencyMapper.class);
    private final TaskStepRepositoryMapper scopes = mock(TaskStepRepositoryMapper.class);
    private final RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
    private final ProjectRepositoryMapper projectRepositories = mock(ProjectRepositoryMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final EventService events = mock(EventService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final DefaultAgentProvisioner defaultAgents = mock(DefaultAgentProvisioner.class);
    private final ContextService contextService = mock(ContextService.class);
    private final TaskContextSnapshotCodec contextSnapshotCodec = new TaskContextSnapshotCodec(new ObjectMapper());
    private final TaskService service = new TaskService(tasks, workspaces, repositories, steps, dependencies, scopes,
            groups, projectRepositories, projects, messages, agents, access, events, eventPublisher, defaultAgents,
            contextService, contextSnapshotCodec);

    @Test
    void createPersistsOneWorkspaceAndMultipleRepositories() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID(), frontend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(projectRepositories.selectById(frontend)).thenReturn(repository(frontend, projectId));
        when(repositories.selectByWorkspace(any(UUID.class)))
                .thenReturn(List.of(worktree(backend, "repo-1", "base", "feat/task-x"),
                        worktree(frontend, "repo-2", "base", "feat/task-x")));

        TaskResponse result = service.create(projectId, actor, request(groupId, List.of(backend, frontend)));

        assertEquals(2, result.getRepositoryIds().size());
        assertEquals("PROVISIONING", result.getWorkspaceStatus());
        assertEquals(2, result.getRepositories().size());
        assertEquals(backend.toString(), result.getRepositories().getFirst().getRepositoryId());
        assertEquals("feat/task-x", result.getRepositories().getFirst().getSourceBranch());
        verify(workspaces).insert(any(WorkspaceEntity.class));
        verify(repositories, times(2)).insertLink(any(), any(), any(), any(), any());
    }

    @Test
    void createPublishesTaskCreatedEvent() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(repositories.selectByWorkspace(any(UUID.class)))
                .thenReturn(List.of(worktree(backend, "repo-1", "base", "feat/task-x")));

        service.create(projectId, actor, request(groupId, List.of(backend)));

        ArgumentCaptor<TaskCreatedEvent> captor = ArgumentCaptor.forClass(TaskCreatedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertEquals(projectId, captor.getValue().projectId());
        assertNotNull(captor.getValue().taskId());
    }

    @Test
    void createPersistsFrozenContextSnapshot() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(repositories.selectByWorkspace(any(UUID.class)))
                .thenReturn(List.of(worktree(backend, "repo-1", "base", "feat/task-x")));
        GroupContext context = new GroupContext(groupId.toString(), projectId.toString(), "需求", "背景", List.of(),
                List.of(), List.of(), List.of());
        when(contextService.buildTaskSnapshot(actor, projectId, groupId, null)).thenReturn(context);

        service.create(projectId, actor, request(groupId, List.of(backend)));

        ArgumentCaptor<TaskEntity> captured = ArgumentCaptor.forClass(TaskEntity.class);
        verify(tasks).insert(captured.capture());
        assertThat(captured.getValue().getContextSnapshot()).containsEntry("version", 1).containsKey("groupContext");
        verify(contextService).buildTaskSnapshot(actor, projectId, groupId, null);
    }

    @Test
    void createRejectsProjectMainGroup() {
        UUID projectId = UUID.randomUUID(), groupId = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "PROJECT_MAIN", "ACTIVE"));
        ApiException error = assertThrows(ApiException.class,
                () -> service.create(projectId, UUID.randomUUID(), request(groupId, List.of(UUID.randomUUID()))));
        assertEquals("ACTIVE_REQUIREMENT_GROUP_REQUIRED", error.code());
    }

    @Test
    void createRejectsTriggerMessageFromAnotherGroup() {
        UUID projectId = UUID.randomUUID(), groupId = UUID.randomUUID(), messageId = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        MessageEntity message = new MessageEntity();
        message.setId(messageId);
        message.setRequirementGroupId(UUID.randomUUID());
        when(messages.selectById(messageId)).thenReturn(message);
        TaskCreateRequest request = request(groupId, List.of(UUID.randomUUID()));
        request.setTriggerMessageId(messageId);
        ApiException error = assertThrows(ApiException.class,
                () -> service.create(projectId, UUID.randomUUID(), request));
        assertEquals("TRIGGER_MESSAGE_GROUP_MISMATCH", error.code());
    }

    @Test
    void createRejectsContinuationFromDifferentRequirementGroup() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID otherGroup = UUID.randomUUID(), continuationTaskId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        TaskEntity continuation = task(continuationTaskId, projectId, actor);
        continuation.setRequirementGroupId(otherGroup);
        continuation.setWorkspaceId(workspaceId);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setProjectId(projectId);
        when(tasks.selectById(continuationTaskId)).thenReturn(continuation);
        when(workspaces.selectByIdForUpdate(workspaceId)).thenReturn(workspace);
        TaskCreateRequest request = request(groupId, List.of());
        request.setWorkspaceId(workspaceId);
        request.setContinuationOfTaskId(continuationTaskId);

        ApiException error = assertThrows(ApiException.class,
                () -> service.create(projectId, actor, request));

        assertEquals("WORKSPACE_CONTINUATION_GROUP_MISMATCH", error.code());
    }

    @Test
    void createContinuationReusesWorkspaceAndInheritsRepositories() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID continuationTaskId = UUID.randomUUID(), workspaceId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        TaskEntity continuation = task(continuationTaskId, projectId, actor);
        continuation.setRequirementGroupId(groupId);
        continuation.setWorkspaceId(workspaceId);
        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(workspaceId);
        workspace.setProjectId(projectId);
        workspace.setStatus("READY");
        when(tasks.selectById(continuationTaskId)).thenReturn(continuation);
        when(workspaces.selectByIdForUpdate(workspaceId)).thenReturn(workspace);
        when(projectRepositories.selectById(repositoryId)).thenReturn(repository(repositoryId, projectId));
        when(repositories.selectByWorkspace(workspaceId))
                .thenReturn(List.of(worktree(repositoryId, "repo-1", "base", "feat/task-x")));
        TaskCreateRequest request = request(groupId, List.of());
        request.setWorkspaceId(workspaceId);
        request.setContinuationOfTaskId(continuationTaskId);

        TaskResponse result = service.create(projectId, actor, request);

        assertEquals(workspaceId.toString(), result.getWorkspaceId());
        assertEquals(continuationTaskId.toString(), result.getContinuationOfTaskId());
        assertEquals(List.of(repositoryId.toString()), result.getRepositoryIds());
        verify(workspaces, never()).insert(any(WorkspaceEntity.class));
    }

    @Test
    void replaceAgentOnlyAllowsPendingStep() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID(), stepId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        TaskStepEntity step = new TaskStepEntity();
        step.setId(stepId); step.setTaskId(taskId); step.setStatus("RUNNING");
        when(tasks.selectById(taskId)).thenReturn(task);
        when(steps.selectById(stepId)).thenReturn(step);
        ApiException error = assertThrows(ApiException.class,
                () -> service.replaceAssignedAgent(projectId, taskId, stepId, actor, UUID.randomUUID()));
        assertEquals("TASK_STEP_NOT_PENDING", error.code());
        verify(steps, never()).updateById(any(TaskStepEntity.class));
    }

    @Test
    void pendingStepAgentReplacementReturnsPersistedScopes() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID(), agentId = UUID.randomUUID(), repositoryId = UUID.randomUUID(), teamId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        TaskStepEntity step = new TaskStepEntity();
        step.setId(stepId); step.setTaskId(taskId); step.setRole("DEVELOPER"); step.setStatus("PENDING");
        ProjectEntity project = new ProjectEntity(); project.setId(projectId); project.setTeamId(teamId);
        AgentEntity agent = new AgentEntity(); agent.setId(agentId); agent.setTeamId(teamId); agent.setRole("DEVELOPER");
        agent.setVisibility("TEAM"); agent.setStatus("ACTIVE");
        TaskStepRepositoryEntity scope = new TaskStepRepositoryEntity(); scope.setTaskStepId(stepId);
        scope.setProjectRepositoryId(repositoryId); scope.setAccessMode("WRITE");
        when(tasks.selectById(taskId)).thenReturn(task); when(steps.selectById(stepId)).thenReturn(step);
        when(projects.selectById(projectId)).thenReturn(project); when(agents.selectById(agentId)).thenReturn(agent);
        when(scopes.selectByStep(stepId)).thenReturn(List.of(scope));

        TaskStepResponse response = service.replaceAssignedAgent(projectId, taskId, stepId, actor, agentId);

        assertEquals(agentId.toString(), response.getAssignedAgentId());
        assertEquals("WRITE", response.getRepositoryScopes().getFirst().getAccessMode());
        verify(steps).updateById(step);
    }

    @Test
    void revisionStepMayDependOnExistingStepAndKeepsPerRepositoryAccessMode() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        UUID existingId = UUID.randomUUID(), newId = UUID.randomUUID(), repositoryId = UUID.randomUUID();
        when(tasks.selectById(taskId)).thenReturn(task(taskId, projectId, actor));
        UUID workspaceId = UUID.randomUUID();
        task(taskId, projectId, actor).setWorkspaceId(workspaceId);
        TaskEntity task = task(taskId, projectId, actor); task.setWorkspaceId(workspaceId);
        when(tasks.selectById(taskId)).thenReturn(task);
        WorkspaceRepositoryEntity taskRepository = new WorkspaceRepositoryEntity(); taskRepository.setWorkspaceId(workspaceId);
        taskRepository.setProjectRepositoryId(repositoryId);
        when(repositories.selectByWorkspace(workspaceId)).thenReturn(List.of(taskRepository));
        TaskStepEntity existing = new TaskStepEntity(); existing.setId(existingId); existing.setTaskId(taskId);
        when(steps.selectList(any())).thenReturn(List.of(existing));
        TaskStepCreateRequest request = step(newId, repositoryId, "WRITE");
        request.setDependencyIds(List.of(existingId));

        List<TaskStepResponse> response = service.addSteps(projectId, taskId, actor, List.of(request));

        assertEquals("WRITE", response.getFirst().getRepositoryScopes().getFirst().getAccessMode());
        verify(dependencies).insertLink(newId, existingId);
        verify(scopes).insertLink(newId, repositoryId, "WRITE");
    }

    @Test
    void materializedPlanRejectsAppendingSteps() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        task.setPlanMaterializedAt(java.time.LocalDateTime.now());
        when(tasks.selectById(taskId)).thenReturn(task);

        ApiException exception = assertThrows(ApiException.class,
                () -> service.addSteps(projectId, taskId, actor, List.of()));

        assertEquals("TASK_STEP_PLAN_FROZEN", exception.code());
        verifyNoInteractions(workspaces, repositories, steps);
    }

    @Test
    void cancelPendingTaskMarksCancelledAndPublishesEvent() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID(), workspaceId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        task.setStatus("PENDING"); task.setRequirementGroupId(groupId); task.setWorkspaceId(workspaceId);
        when(tasks.selectById(taskId)).thenReturn(task);

        TaskResponse result = service.cancel(projectId, taskId, actor);

        assertEquals("CANCELLED", result.getStatus());
        assertEquals("CANCELLED", task.getStatus());
        verify(tasks).updateById(task);
        verify(events).publish(any(), any(), eq("task.updated"), eq(taskId.toString()), any(Map.class));
    }

    @Test
    void cancelRunningTaskMarksCancelling() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        task.setStatus("RUNNING");
        when(tasks.selectById(taskId)).thenReturn(task);

        TaskResponse result = service.cancel(projectId, taskId, actor);

        assertEquals("CANCELLING", result.getStatus());
        assertEquals("CANCELLING", task.getStatus());
    }

    @Test
    void cancelSucceededTaskRejected() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), taskId = UUID.randomUUID();
        TaskEntity task = task(taskId, projectId, actor);
        task.setStatus("SUCCEEDED");
        when(tasks.selectById(taskId)).thenReturn(task);

        ApiException error = assertThrows(ApiException.class,
                () -> service.cancel(projectId, taskId, actor));

        assertEquals("TASK_NOT_CANCELLABLE", error.code());
        verify(tasks, never()).updateById(any(TaskEntity.class));
    }

    private TaskCreateRequest request(UUID groupId, List<UUID> repositoryIds) {
        TaskCreateRequest request = new TaskCreateRequest();
        request.setRequirementGroupId(groupId); request.setTitle("login"); request.setRequirement("implement login");
        request.setRepositoryIds(repositoryIds); return request;
    }

    private TaskStepCreateRequest step(UUID id, UUID repositoryId, String accessMode) {
        TaskRepositoryScopeRequest scope = new TaskRepositoryScopeRequest();
        scope.setRepositoryId(repositoryId); scope.setAccessMode(accessMode);
        TaskStepCreateRequest request = new TaskStepCreateRequest();
        request.setId(id); request.setTitle("revise"); request.setInstruction("apply review feedback");
        request.setRole("DEVELOPER"); request.setRepositoryScopes(List.of(scope)); return request;
    }

    private RequirementGroupEntity group(UUID id, UUID projectId, String type, String status) {
        RequirementGroupEntity group = new RequirementGroupEntity(); group.setId(id); group.setProjectId(projectId);
        group.setGroupType(type); group.setStatus(status); return group;
    }

    private ProjectRepositoryEntity repository(UUID id, UUID projectId) {
        ProjectRepositoryEntity repository = new ProjectRepositoryEntity(); repository.setId(id);
        repository.setProjectId(projectId); repository.setStatus("ACTIVE"); return repository;
    }

    private WorkspaceRepositoryEntity worktree(UUID repositoryId, String path, String baseCommit, String sourceBranch) {
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setProjectRepositoryId(repositoryId); worktree.setWorkspacePath(path);
        worktree.setBaseCommit(baseCommit); worktree.setSourceBranch(sourceBranch); worktree.setHeadCommit(null);
        return worktree;
    }

    private TaskEntity task(UUID id, UUID projectId, UUID creator) {
        TaskEntity task = new TaskEntity(); task.setId(id); task.setProjectId(projectId); task.setCreatedBy(creator);
        return task;
    }
}
