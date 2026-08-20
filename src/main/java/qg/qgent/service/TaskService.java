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
import qg.qgent.orchestration.DeliveryMode;
import qg.qgent.orchestration.TaskContextSnapshotCodec;
import qg.qgent.orchestration.TaskStepExecutionMode;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final DefaultAgentProvisioner defaultAgents;
    private final ContextService contextService;
    private final TaskContextSnapshotCodec contextSnapshotCodec;
    /** 续作前的工作分支 MR 锁定门禁；纯领域单测可不注入。 */
    private WorkBranchDevelopmentGuard developmentGuard;

    /**
     * Creates the task-domain service with all persistence and authorization
     * collaborators.
     */
    public TaskService(TaskMapper tasks, WorkspaceMapper workspaces, WorkspaceRepositoryMapper repositories,
                       TaskStepMapper steps, TaskStepDependencyMapper dependencies, TaskStepRepositoryMapper scopes,
                       RequirementGroupMapper groups, ProjectRepositoryMapper projectRepositories, ProjectMapper projects,
                       MessageMapper messages, AgentMapper agents, ProjectAccessService access, EventService eventService,
                       ApplicationEventPublisher eventPublisher, DefaultAgentProvisioner defaultAgents,
                       ContextService contextService, TaskContextSnapshotCodec contextSnapshotCodec) {
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
        this.defaultAgents = defaultAgents;
        this.contextService = contextService;
        this.contextSnapshotCodec = contextSnapshotCodec;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDevelopmentGuard(WorkBranchDevelopmentGuard developmentGuard) {
        this.developmentGuard = developmentGuard;
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
        if (body.getDeliveryMode() != null && !DeliveryMode.isValid(body.getDeliveryMode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DELIVERY_MODE",
                    "交付模式仅支持 DIFF_FIRST 或 MR_FIRST");
        }
        // baseRef 只接受分支名：Git Store 同步按 refs/heads/<name> fetch，commit SHA 形态
        // 必然找不到远端分支，提前拒绝而不是在 Worker 侧报晦涩错误。
        if (body.getBaseRef() != null && (!body.getBaseRef().trim().matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,254}")
                || body.getBaseRef().trim().startsWith("/") || body.getBaseRef().trim().contains("//")
                || body.getBaseRef().trim().contains("..") || body.getBaseRef().trim().endsWith(".lock")
                || body.getBaseRef().trim().matches("[0-9a-fA-F]{40,64}"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BASE_REF",
                    "baseRef 必须是合法分支名，且不能是 commit SHA 或 Git 引用路径");
        }
        // 锁定项目行串行化同项目内的 Task 创建，保证 display_code 序号在项目内单调且不重复（沿用消息序号的持行锁模式）。
        ProjectEntity project = projects.selectByIdForUpdate(projectId);
        // 任务发起惰性兜底：确保团队默认 Agent 一定在位（存量团队 / 部署间隙 / 建团队失败重试等场景），
        // 幂等 + 唯一索引并发安全，两人同时发起任务也不会重复创建默认 Agent。
        if (project != null && project.getTeamId() != null) {
            defaultAgents.ensureForTeam(project.getTeamId());
        }
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
            if (!group.getId().equals(continuation.getRequirementGroupId())) {
                throw validation("WORKSPACE_CONTINUATION_GROUP_MISMATCH",
                        "The continued Task must belong to the current Requirement Group");
            }
            if (developmentGuard != null) {
                developmentGuard.requireContinuationAllowed(projectId, workspace.getId());
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
            // 软解绑（UNBOUND）的仓库不得用于新任务；历史任务按原 ID 读取不受影响
            if (!"ACTIVE".equals(repository.getStatus())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PROJECT_REPOSITORY_UNBOUND",
                        "仓库已从项目解绑，不能创建新任务");
            }
        }
        // 创建事务内冻结默认上下文。ContextService 首先校验需求群成员关系，拒绝未加入该群的项目成员；
        // 触发消息即使不在最近 50 条窗口内也会完整写入快照。仓库清单以本次实际生效的 repositoryIds
        // 为准（新建 = 请求指定，续作 = Workspace worktree 列表），不再回读需求群绑定记录，避免
        // Workspace 挂载与 Agent 上下文仓库不一致。
        Map<String, Object> contextSnapshot = contextSnapshotCodec.encode(
                contextService.buildTaskSnapshot(actor, projectId, group.getId(), body.getTriggerMessageId(),
                        repositoryIds));
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
        task.setContextSnapshot(contextSnapshot);
        task.setStatus("PLANNING");
        task.setDeliveryMode(resolveDeliveryMode(body, continuation));
        task.setCreatedBy(actor);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        tasks.insert(task);
        publishTaskUpdated(task);

        if (!reuseWorkspace) {
            int index = 1;
            for (UUID repositoryId : repositoryIds) {
                repositories.insertLink(workspace.getId(), repositoryId, "repo-" + index++, body.getBaseRef(),
                        featureBranch(task.getId(), body.getTitle()));
            }
        }
        eventPublisher.publishEvent(new TaskCreatedEvent(projectId, task.getId()));
        return response(task, workspace);
    }

    /**
     * 返回项目中由指定触发消息创建的 Task；不存在时返回 null。
     * 供显式触发端点幂等返回已有 Task，避免同一触发消息重复创建（尤其引用 DIFF 续作）。
     *
     * @param projectId       项目 ID（归属校验）
     * @param triggerMessageId 触发消息 ID
     * @param actor           当前用户 ID
     * @return 已有 Task 视图；不存在时返回 null
     */
    public TaskResponse findByTriggerMessage(UUID projectId, UUID triggerMessageId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        if (triggerMessageId == null) {
            return null;
        }
        TaskEntity task = tasks.selectOne(Wrappers.<TaskEntity>lambdaQuery()
                .eq(TaskEntity::getProjectId, projectId)
                .eq(TaskEntity::getTriggerMessageId, triggerMessageId));
        return task == null ? null : response(task);
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
        if (task.getPlanMaterializedAt() != null || (task.getStatus() != null
                && !Set.of("PLANNING", "PENDING").contains(task.getStatus()))) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_STEP_PLAN_FROZEN",
                    "计划已物化或任务已开始执行，不能追加步骤");
        }
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
        step.setExecutionMode(TaskStepExecutionMode.resolve(null, step.getRole()).name());
        step.setAssignedAgentId(request.getAssignedAgentId());
        step.setAcceptanceCriteria(request.getAcceptanceCriteria());
        step.setRequiredCapabilities(request.getRequiredCapabilities());
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
                step.getRequiredCapabilities(), step.getStatus(), repositoryScopes);
    }

    private TaskRepositoryScopeRequest scopeResponse(TaskStepRepositoryEntity entity) {
        TaskRepositoryScopeRequest response = new TaskRepositoryScopeRequest();
        response.setRepositoryId(entity.getProjectRepositoryId());
        response.setAccessMode(entity.getAccessMode());
        return response;
    }

    /**
     * 新 Workspace 各仓库共享的功能分支名：&lt;type&gt;/&lt;标题slug&gt;-&lt;id前缀&gt;。
     * type 不固定 feat，按标题内容识别 conventional 类型（"fix: 登录报错"/"修复…"/"fix login bug" → fix，
     * "实现…"/"新增…" → feat），识别不到兜底 feat；标题经 {@link #slugify} 转为 ASCII 短横线段
     * （截断 24 个码点），id 前缀取 UUIDv7 去横线后的前 12 位
     * （48 位毫秒时间戳，毫秒级唯一；避免整段 UUID 使分支名过长且像乱码）。
     * 分支名必须为 ASCII：沙箱 Worker 的 {@code WorkspaceRepositoryRequest} 只接受
     * {@code [A-Za-z0-9][A-Za-z0-9._/-]*}，中文等非 ASCII 字符会导致任务启动被拒绝。
     */
    private static String featureBranch(UUID taskId, String title) {
        String[] typeAndName = detectType(title);
        return typeAndName[0] + "/" + slugify(typeAndName[1]) + "-"
                + taskId.toString().replace("-", "").substring(0, 12);
    }

    /** 分支类型前缀（conventional 命名规范）。 */
    private static final List<String> BRANCH_TYPES =
            List.of("feat", "fix", "chore", "docs", "refactor", "test", "perf", "style", "build", "ci", "revert");

    /** conventional 风格 "type: 标题" 或 "type(scope): 标题"，type 必须位于 BRANCH_TYPES。 */
    private static final Pattern BRANCH_TYPE_COLON =
            Pattern.compile("^([a-z][a-z0-9]*)(?:\\([^)]*\\))?:\\s*(.+)$");

    /**
     * 按优先级排列的中文类型关键字（小写，命中即采用该类型，先出现者优先）。
     * 英文类型关键字只通过标题开头识别（colon/前缀两种），不在此做包含式匹配，避免 "prefix" 等误命中 "fix"。
     */
    private static final List<String[]> BRANCH_TYPE_KEYWORDS = List.of(
            new String[]{"fix", "修复", "缺陷", "打补丁"},
            new String[]{"docs", "文档", "注释", "说明"},
            new String[]{"refactor", "重构", "清理", "重命名"},
            new String[]{"test", "测试", "用例"},
            new String[]{"perf", "性能", "优化", "提速"},
            new String[]{"revert", "回滚", "撤销"},
            new String[]{"chore", "维护", "杂项"},
            new String[]{"style", "样式", "格式化"},
            new String[]{"feat", "实现", "新增", "添加", "开发", "接入"}
    );

    /**
     * 从标题识别 [type, 名称]。识别顺序：conventional colon 风格 &gt; 英文类型词开头 &gt; 中文关键字包含式 &gt; 兜底 feat。
     */
    private static String[] detectType(String title) {
        if (title == null || title.isBlank()) {
            return new String[]{"feat", "task"};
        }
        String trimmed = title.trim();
        Matcher colon = BRANCH_TYPE_COLON.matcher(trimmed);
        if (colon.matches() && BRANCH_TYPES.contains(colon.group(1))) {
            return new String[]{colon.group(1), colon.group(2)};
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String type : BRANCH_TYPES) {
            if (lower.startsWith(type + " ") || lower.startsWith(type + "(")) {
                return new String[]{type, trimmed.substring(type.length()).trim()};
            }
        }
        for (String[] keywords : BRANCH_TYPE_KEYWORDS) {
            for (int i = 1; i < keywords.length; i++) {
                if (lower.contains(keywords[i])) {
                    return new String[]{keywords[0], trimmed};
                }
            }
        }
        return new String[]{"feat", trimmed};
    }

    /**
     * 将标题片段折叠为 ASCII 短横线段：只保留小写字母与数字，其余字符（含中文等非 ASCII 字母/数字、
     * 符号）统一折叠为 '-',连续短横线合并、去除首尾短横线；全非 ASCII 或空时兜底返回 "task"。
     * 因为 {@link #featureBranch} 生成的分支名会被沙箱 Worker 校验（仅接受 ASCII），此处不允许
     * 保留 Unicode 字符，避免中文标题生成的分支名导致任务启动失败。
     */
    private static String slugify(String title) {
        if (title == null) {
            return "task";
        }
        String slug = title.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-|-$", "");
        if (slug.isEmpty()) {
            return "task";
        }
        if (slug.codePointCount(0, slug.length()) > 24) {
            slug = slug.substring(0, slug.offsetByCodePoints(0, 24)).replaceAll("-+$", "");
        }
        return slug;
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

    /**
     * 交付模式解析优先级：用户显式指定（已校验）&gt; 续作沿用源 Task &gt; 空（Plan 物化时自动判定）。
     * 续作沿用原 Task 的 deliveryMode，保证多轮迭代中途不换交付路线。
     */
    private String resolveDeliveryMode(TaskCreateRequest body, TaskEntity continuation) {
        if (body.getDeliveryMode() != null) {
            return body.getDeliveryMode();
        }
        return continuation == null ? null : continuation.getDeliveryMode();
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
