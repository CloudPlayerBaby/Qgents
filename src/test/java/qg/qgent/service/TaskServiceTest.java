package qg.qgent.service;

import org.junit.jupiter.api.Test;
import qg.qgent.api.ApiException;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Security and persistence behavior tests for the confirmed Task workflow model. */
class TaskServiceTest {
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final WorkspaceMapper workspaces = mock(WorkspaceMapper.class);
    private final TaskRepositoryMapper repositories = mock(TaskRepositoryMapper.class);
    private final TaskStepMapper steps = mock(TaskStepMapper.class);
    private final TaskStepDependencyMapper dependencies = mock(TaskStepDependencyMapper.class);
    private final TaskStepRepositoryMapper scopes = mock(TaskStepRepositoryMapper.class);
    private final RequirementGroupMapper groups = mock(RequirementGroupMapper.class);
    private final ProjectRepositoryMapper projectRepositories = mock(ProjectRepositoryMapper.class);
    private final ProjectMapper projects = mock(ProjectMapper.class);
    private final MessageMapper messages = mock(MessageMapper.class);
    private final AgentMapper agents = mock(AgentMapper.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final TaskService service = new TaskService(tasks, workspaces, repositories, steps, dependencies, scopes,
            groups, projectRepositories, projects, messages, agents, access);

    @Test
    void createPersistsOneWorkspaceAndMultipleRepositories() {
        UUID projectId = UUID.randomUUID(), actor = UUID.randomUUID(), groupId = UUID.randomUUID();
        UUID backend = UUID.randomUUID(), frontend = UUID.randomUUID();
        when(groups.selectById(groupId)).thenReturn(group(groupId, projectId, "REQUIREMENT", "ACTIVE"));
        when(projectRepositories.selectById(backend)).thenReturn(repository(backend, projectId));
        when(projectRepositories.selectById(frontend)).thenReturn(repository(frontend, projectId));

        TaskResponse result = service.create(projectId, actor, request(groupId, List.of(backend, frontend)));

        assertEquals(2, result.getRepositoryIds().size());
        verify(workspaces).insert(any(WorkspaceEntity.class));
        verify(repositories, times(2)).insertLink(any(), any(), any(), any());
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
        TaskRepositoryEntity taskRepository = new TaskRepositoryEntity(); taskRepository.setTaskId(taskId);
        taskRepository.setProjectRepositoryId(repositoryId);
        when(repositories.selectByTask(taskId)).thenReturn(List.of(taskRepository));
        TaskStepEntity existing = new TaskStepEntity(); existing.setId(existingId); existing.setTaskId(taskId);
        when(steps.selectList(any())).thenReturn(List.of(existing));
        TaskStepCreateRequest request = step(newId, repositoryId, "WRITE");
        request.setDependencyIds(List.of(existingId));

        List<TaskStepResponse> response = service.addSteps(projectId, taskId, actor, List.of(request));

        assertEquals("WRITE", response.getFirst().getRepositoryScopes().getFirst().getAccessMode());
        verify(dependencies).insertLink(newId, existingId);
        verify(scopes).insertLink(newId, repositoryId, "WRITE");
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
        repository.setProjectId(projectId); return repository;
    }

    private TaskEntity task(UUID id, UUID projectId, UUID creator) {
        TaskEntity task = new TaskEntity(); task.setId(id); task.setProjectId(projectId); task.setCreatedBy(creator);
        return task;
    }
}
