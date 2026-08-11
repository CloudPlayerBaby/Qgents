package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Coordinates user-visible Tasks and Planner-defined TaskSteps.
 * All mutations authorize the authenticated actor and persist metadata only; this service never controls Git,
 * Workspace files, Sandbox containers or Agent execution.
 */
@Service
public class TaskService {
    private final TaskMapper tasks;
    private final WorkspaceMapper workspaces;
    private final TaskRepositoryMapper repositories;
    private final TaskStepMapper steps;
    private final TaskStepDependencyMapper dependencies;
    private final TaskStepRepositoryMapper scopes;
    private final RequirementGroupMapper groups;
    private final ProjectRepositoryMapper projectRepositories;
    private final ProjectMapper projects;
    private final MessageMapper messages;
    private final AgentMapper agents;
    private final ProjectAccessService access;

    /** Creates the task-domain service with all persistence and authorization collaborators. */
    public TaskService(TaskMapper tasks, WorkspaceMapper workspaces, TaskRepositoryMapper repositories,
            TaskStepMapper steps, TaskStepDependencyMapper dependencies, TaskStepRepositoryMapper scopes,
            RequirementGroupMapper groups, ProjectRepositoryMapper projectRepositories, ProjectMapper projects,
            MessageMapper messages, AgentMapper agents, ProjectAccessService access) {
        this.tasks = tasks;
        this.workspaces = workspaces;
        this.repositories = repositories;
        this.steps = steps;
        this.dependencies = dependencies;
        this.scopes = scopes;
        this.groups = groups;
        this.projectRepositories = projectRepositories;
        this.projects = projects;
        this.messages = messages;
        this.agents = agents;
        this.access = access;
    }

    /**
     * Creates one Task, one persistent Workspace root and one worktree metadata row per selected repository.
     * The optional trigger message must belong to the same active REQUIREMENT group.
     */
    @Transactional
    public TaskResponse create(UUID projectId, UUID actor, TaskCreateRequest body) {
        access.requireProjectMember(projectId, actor);
        RequirementGroupEntity group = groups.selectById(body.getRequirementGroupId());
        if (group == null || !projectId.equals(group.getProjectId()) || !"REQUIREMENT".equals(group.getGroupType())
                || !"ACTIVE".equals(group.getStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACTIVE_REQUIREMENT_GROUP_REQUIRED",
                    "任务必须来自当前项目中有效的需求群");
        }
        if (body.getTriggerMessageId() != null) {
            MessageEntity message = messages.selectById(body.getTriggerMessageId());
            if (message == null || !group.getId().equals(message.getRequirementGroupId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "TRIGGER_MESSAGE_GROUP_MISMATCH",
                        "触发消息不属于当前需求群");
            }
        }
        List<UUID> repositoryIds = body.getRepositoryIds().stream().distinct().toList();
        for (UUID repositoryId : repositoryIds) {
            ProjectRepositoryEntity repository = projectRepositories.selectById(repositoryId);
            if (repository == null || !projectId.equals(repository.getProjectId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPOSITORY_NOT_IN_PROJECT",
                        "仓库未绑定到当前项目");
            }
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TaskEntity task = new TaskEntity();
        task.setId(UuidV7.next());
        task.setProjectId(projectId);
        task.setRequirementGroupId(group.getId());
        task.setTriggerMessageId(body.getTriggerMessageId());
        task.setTitle(body.getTitle().trim());
        task.setRequirement(body.getRequirement().trim());
        task.setStatus("PLANNING");
        task.setCreatedBy(actor);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        tasks.insert(task);

        WorkspaceEntity workspace = new WorkspaceEntity();
        workspace.setId(UuidV7.next());
        workspace.setTaskId(task.getId());
        workspace.setProjectId(projectId);
        workspace.setStorageKey("tasks/" + task.getId());
        workspace.setStatus("PROVISIONING");
        workspace.setCreatedAt(now);
        workspace.setUpdatedAt(now);
        workspaces.insert(workspace);
        int index = 1;
        for (UUID repositoryId : repositoryIds) {
            repositories.insertLink(task.getId(), repositoryId, "repo-" + index++, body.getBaseRef());
        }
        return response(task, workspace, repositoryIds);
    }

    /** Lists project Tasks visible to the authenticated project member. */
    public List<TaskResponse> list(UUID projectId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        return tasks.selectList(Wrappers.<TaskEntity>lambdaQuery().eq(TaskEntity::getProjectId, projectId)
                .orderByDesc(TaskEntity::getCreatedAt)).stream().map(this::response).toList();
    }

    /** Returns one project-scoped Task without exposing host storage paths. */
    public TaskResponse get(UUID projectId, UUID taskId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        return response(requireTask(projectId, taskId));
    }

    /**
     * Appends Planner output after validating the dependency DAG and per-repository access scopes.
     * Only the Task creator or a Project Admin can alter the plan. Existing steps from the same Task may be dependencies.
     */
    @Transactional
    public List<TaskStepResponse> addSteps(UUID projectId, UUID taskId, UUID actor,
            List<TaskStepCreateRequest> requests) {
        TaskEntity task = requireTask(projectId, taskId);
        requireTaskManager(task, actor);
        workspaces.selectByTaskForUpdate(taskId);
        Set<UUID> allowedRepositories = repositories.selectByTask(taskId).stream()
                .map(TaskRepositoryEntity::getProjectRepositoryId).collect(Collectors.toSet());
        Set<UUID> batchIds = requests.stream().map(TaskStepCreateRequest::getId).collect(Collectors.toSet());
        if (batchIds.size() != requests.size()) {
            throw validation("TASK_STEP_ID_DUPLICATE", "步骤 ID 不得重复");
        }
        Set<UUID> existingIds = steps.selectList(Wrappers.<TaskStepEntity>lambdaQuery()
                .eq(TaskStepEntity::getTaskId, taskId)).stream().map(TaskStepEntity::getId).collect(Collectors.toSet());
        for (TaskStepCreateRequest request : requests) {
            validateAgent(projectId, actor, request.getAssignedAgentId(), request.getRole());
            for (TaskRepositoryScopeRequest scope : request.getRepositoryScopes()) {
                if (!allowedRepositories.contains(scope.getRepositoryId())) {
                    throw validation("TASK_STEP_REPOSITORY_OUT_OF_SCOPE", "步骤仓库不属于任务");
                }
            }
            for (UUID dependencyId : Optional.ofNullable(request.getDependencyIds()).orElse(List.of())) {
                if (dependencyId.equals(request.getId())
                        || (!batchIds.contains(dependencyId) && !existingIds.contains(dependencyId))) {
                    throw validation("TASK_STEP_DEPENDENCY_INVALID", "步骤依赖必须属于同一个任务");
                }
            }
        }
        validateAcyclic(requests);
        int sequence = existingIds.size() + 1;
        List<TaskStepResponse> result = new ArrayList<>();
        for (TaskStepCreateRequest request : requests) {
            TaskStepEntity step = toEntity(taskId, sequence++, request);
            steps.insert(step);
            for (UUID dependencyId : Optional.ofNullable(request.getDependencyIds()).orElse(List.of())) {
                dependencies.insertLink(step.getId(), dependencyId);
            }
            for (TaskRepositoryScopeRequest scope : request.getRepositoryScopes()) {
                scopes.insertLink(step.getId(), scope.getRepositoryId(), scope.getAccessMode());
            }
            result.add(stepResponse(step, request.getRepositoryScopes()));
        }
        return result;
    }

    /** Replaces an assigned Agent only while the step is PENDING and returns its persisted repository scopes. */
    @Transactional
    public TaskStepResponse replaceAssignedAgent(UUID projectId, UUID taskId, UUID stepId, UUID actor, UUID agentId) {
        TaskEntity task = requireTask(projectId, taskId);
        requireTaskManager(task, actor);
        workspaces.selectByTaskForUpdate(taskId);
        TaskStepEntity step = steps.selectById(stepId);
        if (step == null || !taskId.equals(step.getTaskId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_STEP_NOT_FOUND", "任务步骤不存在");
        }
        if (!"PENDING".equals(step.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_STEP_NOT_PENDING", "仅待执行步骤可以替换 Agent");
        }
        validateAgent(projectId, actor, agentId, step.getRole());
        step.setAssignedAgentId(agentId);
        step.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        steps.updateById(step);
        return stepResponse(step, scopes.selectByStep(stepId).stream().map(this::scopeResponse).toList());
    }

    private void validateAgent(UUID projectId, UUID actor, UUID agentId, String requiredRole) {
        if (agentId == null) {
            return;
        }
        ProjectEntity project = projects.selectById(projectId);
        AgentEntity agent = agents.selectById(agentId);
        if (project == null || agent == null || !project.getTeamId().equals(agent.getTeamId())
                || !"ACTIVE".equals(agent.getStatus()) || !requiredRole.equals(agent.getRole())
                || ("PRIVATE".equals(agent.getVisibility()) && !actor.equals(agent.getCreatedBy()))) {
            throw validation("AGENT_NOT_ASSIGNABLE", "Agent 不属于当前团队、不可见、未启用或角色不匹配");
        }
    }

    private void requireTaskManager(TaskEntity task, UUID actor) {
        access.requireProjectMember(task.getProjectId(), actor);
        if (!actor.equals(task.getCreatedBy())) {
            access.requireProjectAdmin(task.getProjectId(), actor);
        }
    }

    private TaskEntity requireTask(UUID projectId, UUID taskId) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或无权访问");
        }
        return task;
    }

    private TaskStepEntity toEntity(UUID taskId, int sequence, TaskStepCreateRequest request) {
        TaskStepEntity step = new TaskStepEntity();
        step.setId(request.getId());
        step.setTaskId(taskId);
        step.setSequenceNo(sequence);
        step.setTitle(request.getTitle().trim());
        step.setInstruction(request.getInstruction().trim());
        step.setRole(request.getRole().trim());
        step.setAssignedAgentId(request.getAssignedAgentId());
        step.setAcceptanceCriteria(request.getAcceptanceCriteria());
        step.setStatus("PENDING");
        step.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        step.setUpdatedAt(step.getCreatedAt());
        return step;
    }

    private void validateAcyclic(List<TaskStepCreateRequest> requests) {
        Map<UUID, List<UUID>> graph = new HashMap<>();
        Set<UUID> batch = requests.stream().map(TaskStepCreateRequest::getId).collect(Collectors.toSet());
        for (TaskStepCreateRequest request : requests) {
            graph.put(request.getId(), Optional.ofNullable(request.getDependencyIds()).orElse(List.of()).stream()
                    .filter(batch::contains).toList());
        }
        Set<UUID> visiting = new HashSet<>();
        Set<UUID> done = new HashSet<>();
        for (UUID id : graph.keySet()) {
            visit(id, graph, visiting, done);
        }
    }

    private void visit(UUID id, Map<UUID, List<UUID>> graph, Set<UUID> visiting, Set<UUID> done) {
        if (done.contains(id)) return;
        if (!visiting.add(id)) throw validation("TASK_STEP_DEPENDENCY_CYCLE", "步骤依赖不能形成循环");
        for (UUID next : graph.getOrDefault(id, List.of())) visit(next, graph, visiting, done);
        visiting.remove(id);
        done.add(id);
    }

    private TaskResponse response(TaskEntity task) {
        WorkspaceEntity workspace = workspaces.selectOne(Wrappers.<WorkspaceEntity>lambdaQuery()
                .eq(WorkspaceEntity::getTaskId, task.getId()));
        List<UUID> repositoryIds = repositories.selectByTask(task.getId()).stream()
                .map(TaskRepositoryEntity::getProjectRepositoryId).toList();
        return response(task, workspace, repositoryIds);
    }

    private TaskResponse response(TaskEntity task, WorkspaceEntity workspace, List<UUID> repositoryIds) {
        return new TaskResponse(id(task.getId()), id(task.getProjectId()), id(task.getRequirementGroupId()),
                id(task.getTriggerMessageId()), task.getTitle(), task.getRequirement(), task.getStatus(),
                workspace == null ? null : id(workspace.getId()), repositoryIds.stream().map(UUID::toString).toList(),
                id(task.getCreatedBy()), iso(task.getCreatedAt()), iso(task.getUpdatedAt()));
    }

    private TaskStepResponse stepResponse(TaskStepEntity step, List<TaskRepositoryScopeRequest> repositoryScopes) {
        return new TaskStepResponse(id(step.getId()), id(step.getTaskId()), step.getSequenceNo(), step.getTitle(),
                step.getInstruction(), step.getRole(), id(step.getAssignedAgentId()), step.getAcceptanceCriteria(),
                step.getStatus(), repositoryScopes);
    }

    private TaskRepositoryScopeRequest scopeResponse(TaskStepRepositoryEntity entity) {
        TaskRepositoryScopeRequest response = new TaskRepositoryScopeRequest();
        response.setRepositoryId(entity.getProjectRepositoryId());
        response.setAccessMode(entity.getAccessMode());
        return response;
    }

    private ApiException validation(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private String id(UUID value) { return value == null ? null : value.toString(); }
    private String iso(LocalDateTime value) { return value == null ? null : value.toInstant(ZoneOffset.UTC).toString(); }
}
