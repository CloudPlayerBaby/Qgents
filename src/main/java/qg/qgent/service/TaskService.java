package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.ApplicationEventPublisher;
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
 * All mutations authorize the authenticated actor and persist metadata only;
 * this service never controls Git,
 * Workspace files, Sandbox containers or Agent execution.
 * 状态变更（创建、取消）发布 task.updated 项目级事件供前端刷新。
 */
@Service
public class TaskService {
    private final TaskMapper tasks;
    private final WorkspaceMapper workspaces;
    private final WorkspaceRepositoryMapper repositories;
    private final TaskStepMapper steps;
    private final TaskStepDependencyMapper dependencies;
    private final TaskStepRepositoryMapper scopes;
    private final RequirementGroupMapper groups;
    private final ProjectRepositoryMapper projectRepositories;
    private final ProjectMapper projects;
    private final MessageMapper messages;
    private final AgentMapper agents;
    private final ProjectAccessService access;
    private final EventService eventService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Creates the task-domain service with all persistence and authorization
     * collaborators.
     */
    public TaskService(TaskMapper tasks, WorkspaceMapper workspaces, WorkspaceRepositoryMapper repositories,
                       TaskStepMapper steps, TaskStepDependencyMapper dependencies, TaskStepRepositoryMapper scopes,
                       RequirementGroupMapper groups, ProjectRepositoryMapper projectRepositories, ProjectMapper projects,
                       MessageMapper messages, AgentMapper agents, ProjectAccessService access, EventService eventService,
                       ApplicationEventPublisher eventPublisher) {
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
        this.eventService = eventService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Creates a Task with a new Workspace, or explicitly continues a prior Task in
     * its existing Workspace.
     * The optional trigger message must belong to the same active REQUIREMENT
     * group.
     */
    @Transactional
    public TaskResponse create(UUID projectId, UUID actor, TaskCreateRequest body) {
        access.requireProjectMember(projectId, actor);
        // 锁定项目行串行化同项目内的 Task 创建，保证 display_code 序号在项目内单调且不重复（沿用消息序号的持行锁模式）。
        projects.selectByIdForUpdate(projectId);
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
        boolean reuseWorkspace = body.getWorkspaceId() != null || body.getContinuationOfTaskId() != null;
        if (reuseWorkspace && (body.getWorkspaceId() == null || body.getContinuationOfTaskId() == null)) {
            throw validation("WORKSPACE_CONTINUATION_INCOMPLETE",
                    "workspaceId and continuationOfTaskId must be provided together");
        }
        if (reuseWorkspace && body.getRepositoryIds() != null && !body.getRepositoryIds().isEmpty()) {
            throw validation("WORKSPACE_CONTINUATION_REPOSITORIES_FORBIDDEN",
                    "A continuation Task inherits repositories from its Workspace");
        }
        WorkspaceEntity workspace;
        TaskEntity continuation = null;
        List<UUID> repositoryIds;
        if (reuseWorkspace) {
            continuation = tasks.selectById(body.getContinuationOfTaskId());
            workspace = workspaces.selectByIdForUpdate(body.getWorkspaceId());
            if (continuation == null || workspace == null || !projectId.equals(continuation.getProjectId())
                    || !projectId.equals(workspace.getProjectId())
                    || !workspace.getId().equals(continuation.getWorkspaceId())) {
                throw validation("WORKSPACE_CONTINUATION_INVALID",
                        "The continued Task and Workspace must belong to the current Project");
            }
            repositoryIds = repositories.selectByWorkspace(workspace.getId()).stream()
                    .map(WorkspaceRepositoryEntity::getProjectRepositoryId).toList();
            if (repositoryIds.isEmpty()) {
                throw validation("WORKSPACE_HAS_NO_REPOSITORIES", "The reused Workspace has no repository worktrees");
            }
        } else {
            repositoryIds = Optional.ofNullable(body.getRepositoryIds()).orElse(List.of()).stream().distinct().toList();
            if (repositoryIds.isEmpty()) {
                throw validation("TASK_REPOSITORY_REQUIRED", "A new Workspace requires at least one repository");
            }
            workspace = new WorkspaceEntity();
            workspace.setId(UuidV7.next());
            workspace.setProjectId(projectId);
            workspace.setStorageKey("workspaces/" + workspace.getId());
            workspace.setStatus("PROVISIONING");
        }
        for (UUID repositoryId : repositoryIds) {
            ProjectRepositoryEntity repository = projectRepositories.selectById(repositoryId);
            if (repository == null || !projectId.equals(repository.getProjectId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "REPOSITORY_NOT_IN_PROJECT",
                        "仓库未绑定到当前项目");
            }
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!reuseWorkspace) {
            workspace.setCreatedAt(now);
            workspace.setUpdatedAt(now);
            workspaces.insert(workspace);
        }
        TaskEntity task = new TaskEntity();
        task.setId(UuidV7.next());
        task.setProjectId(projectId);
        task.setRequirementGroupId(group.getId());
        task.setTriggerMessageId(body.getTriggerMessageId());
        task.setWorkspaceId(workspace.getId());
        task.setContinuationOfTaskId(continuation == null ? null : continuation.getId());
        task.setTitle(body.getTitle().trim());
        task.setDisplayCode(nextDisplayCode(projectId));
        task.setRequirement(body.getRequirement().trim());
        task.setStatus("PLANNING");
        task.setDeliveryMode("DIFF_FIRST");
        task.setCreatedBy(actor);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        tasks.insert(task);
        publishTaskUpdated(task);

        if (!reuseWorkspace) {
            int index = 1;
            for (UUID repositoryId : repositoryIds) {
                repositories.insertLink(workspace.getId(), repositoryId, "repo-" + index++, body.getBaseRef(),
                        "feat/task-" + task.getId());
            }
        }
        eventPublisher.publishEvent(new TaskCreatedEvent(projectId, task.getId()));
        return response(task, workspace);
    }

    /**
     * 取消任务（异步受理，由 Controller 以 202 返回）。
     * 状态矩阵：PLANNING/PENDING 同步置 CANCELLED；RUNNING 置 CANCELLING，真实终止由受控
     * 执行器在安全检查点完成；SUCCEEDED/FAILED/CANCELLED/CANCELLING 返回 409 拒绝。
     * 仅任务发起人或 Project Admin 可取消。
     */
    @Transactional
    public TaskResponse cancel(UUID projectId, UUID taskId, UUID actor) {
        TaskEntity task = requireTask(projectId, taskId);
        requireTaskManager(task, actor);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        switch (task.getStatus()) {
            case "PLANNING", "PENDING" -> {
                task.setStatus("CANCELLED");
                task.setUpdatedAt(now);
                tasks.updateById(task);
            }
            case "RUNNING" -> {
                task.setStatus("CANCELLING");
                task.setUpdatedAt(now);
                tasks.updateById(task);
            }
            default -> throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_CANCELLABLE", "当前任务状态不可取消");
        }
        publishTaskUpdated(task);
        return response(task);
    }

    /**
     * Appends Planner output after validating the dependency DAG and per-repository
     * access scopes.
     * Only the Task creator or a Project Admin can alter the plan. Existing steps
     * from the same Task may be dependencies.
     */
    @Transactional
    public List<TaskStepResponse> addSteps(UUID projectId, UUID taskId, UUID actor,
                                           List<TaskStepCreateRequest> requests) {
        TaskEntity task = requireTask(projectId, taskId);
        requireTaskManager(task, actor);
        workspaces.selectByIdForUpdate(task.getWorkspaceId());
        Set<UUID> allowedRepositories = repositories.selectByWorkspace(task.getWorkspaceId()).stream()
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId).collect(Collectors.toSet());
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
            eventService.publish(task.getProjectId(), task.getRequirementGroupId(), "task-step.updated",
                    step.getId().toString(), TaskEventPayloads.taskStepUpdated(task.getProjectId(), step));
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

    /**
     * Replaces an assigned Agent only while the step is PENDING and returns its
     * persisted repository scopes.
     */
    @Transactional
    public TaskStepResponse replaceAssignedAgent(UUID projectId, UUID taskId, UUID stepId, UUID actor, UUID agentId) {
        TaskEntity task = requireTask(projectId, taskId);
        requireTaskManager(task, actor);
        workspaces.selectByIdForUpdate(task.getWorkspaceId());
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
        if (done.contains(id))
            return;
        if (!visiting.add(id))
            throw validation("TASK_STEP_DEPENDENCY_CYCLE", "步骤依赖不能形成循环");
        for (UUID next : graph.getOrDefault(id, List.of()))
            visit(next, graph, visiting, done);
        visiting.remove(id);
        done.add(id);
    }

    private TaskResponse response(TaskEntity task) {
        WorkspaceEntity workspace = workspaces.selectById(task.getWorkspaceId());
        return response(task, workspace);
    }

    private TaskResponse response(TaskEntity task, WorkspaceEntity workspace) {
        List<WorkspaceRepositoryEntity> worktrees = workspace == null ? List.of()
                : repositories.selectByWorkspace(workspace.getId());
        List<String> repositoryIds = worktrees.stream().map(w -> id(w.getProjectRepositoryId())).toList();
        List<TaskRepositoryScopeResponse> scopes = worktrees.stream().map(w -> new TaskRepositoryScopeResponse(
                id(w.getProjectRepositoryId()), w.getWorkspacePath(), w.getBaseCommit(), w.getSourceBranch(),
                w.getHeadCommit())).toList();
        return new TaskResponse(id(task.getId()), id(task.getProjectId()), id(task.getRequirementGroupId()),
                id(task.getTriggerMessageId()), task.getTitle(), task.getDisplayCode(), task.getRequirement(),
                task.getStatus(), task.getDeliveryMode(),
                workspace == null ? null : id(workspace.getId()),
                workspace == null ? null : workspace.getStatus(), id(task.getContinuationOfTaskId()),
                repositoryIds, scopes,
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

    /**
     * 在持有项目级行锁的事务内生成项目内唯一、创建后不可变的展示编号，如 T-1、T-2。
     */
    private String nextDisplayCode(UUID projectId) {
        Long max = tasks.selectMaxDisplayCodeSeq(projectId);
        return "T-" + (max == null ? 1 : max + 1);
    }

    private void publishTaskUpdated(TaskEntity task) {
        eventService.publish(task.getProjectId(), task.getRequirementGroupId(), "task.updated",
                task.getId().toString(), TaskEventPayloads.taskUpdated(task));
    }

    private ApiException validation(String code, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private String id(UUID value) {
        return value == null ? null : value.toString();
    }

    private String iso(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC).toString();
    }
}
