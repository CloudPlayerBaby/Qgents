package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.api.PagedApiResponse;
import qg.qgent.dto.*;
import qg.qgent.entity.*;
import qg.qgent.orchestration.ExecutionContentSanitizer;
import qg.qgent.mapper.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 任务中心 / 任务详情 / 执行流程的只读展示摘要组装。
 * <p>
 * 职责：把 Task/TaskStep/TaskRun/产物/总 Diff 等聚合为前端可直接渲染的摘要 DTO，
 * 所有派生字段（executionSummary/attention/capabilities/statusReason 等）均来自真实持久化数据，
 * 不伪造业务字段。列表接口一次性批量加载相关数据，避免逐 Task N+1。
 * 写侧（创建/取消/计划写入）仍由 {@link TaskService} 负责。
 */
@Service
public class TaskDisplayService {
    /**
     * 任务终态集合，用于能力派生。
     */
    private static final Set<String> TERMINAL_TASK_STATUSES = Set.of("SUCCEEDED", "FAILED", "DELIVERY_FAILED",
            "DIFF_REJECTED", "CANCELLED", "CANCELLING");
    private static final Set<String> CANCELLABLE_TASK_STATUSES = Set.of("PLANNING", "PENDING", "RUNNING");
    private static final Set<String> RUN_WAITING = Set.of("WAITING_INPUT", "WAITING_APPROVAL");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int TEXT_EXCERPT_LIMIT = 200;

    private final TaskMapper tasks;
    private final TaskStepMapper steps;
    private final TaskRunMapper runs;
    private final TaskStepDependencyMapper dependencies;
    private final TaskStepRepositoryMapper stepRepositories;
    private final WorkspaceRepositoryMapper worktrees;
    private final WorkspaceMapper workspaces;
    private final ProjectRepositoryMapper projectRepositories;
    private final GitHubRepositoryMapper githubRepositories;
    private final UserMapper users;
    private final AgentMapper agents;
    private final RequirementGroupMapper groups;
    private final InputRequestMapper inputRequests;
    private final TaskExecutionArtifactMapper artifacts;
    private final DiffReviewBatchMapper diffBatches;
    private final DiffMapper diffs;
    private final MergeRequestMapper mergeRequests;
    private final MessageMapper messages;
    private final TaskAcceptanceCriterionMapper acceptanceCriteria;
    private final ProjectAccessService access;
    private final GroupService groupService;
    private final ObjectMapper json;

    public TaskDisplayService(TaskMapper tasks, TaskStepMapper steps, TaskRunMapper runs,
                              TaskStepDependencyMapper dependencies, TaskStepRepositoryMapper stepRepositories,
                              WorkspaceRepositoryMapper worktrees, WorkspaceMapper workspaces,
                              ProjectRepositoryMapper projectRepositories, GitHubRepositoryMapper githubRepositories, UserMapper users,
                              AgentMapper agents, RequirementGroupMapper groups, InputRequestMapper inputRequests,
                              TaskExecutionArtifactMapper artifacts, DiffReviewBatchMapper diffBatches, DiffMapper diffs,
                              MergeRequestMapper mergeRequests, MessageMapper messages,
                              TaskAcceptanceCriterionMapper acceptanceCriteria, ProjectAccessService access,
                              GroupService groupService, ObjectMapper json) {
        this.tasks = tasks;
        this.steps = steps;
        this.runs = runs;
        this.dependencies = dependencies;
        this.stepRepositories = stepRepositories;
        this.worktrees = worktrees;
        this.workspaces = workspaces;
        this.projectRepositories = projectRepositories;
        this.githubRepositories = githubRepositories;
        this.users = users;
        this.agents = agents;
        this.groups = groups;
        this.inputRequests = inputRequests;
        this.artifacts = artifacts;
        this.diffBatches = diffBatches;
        this.diffs = diffs;
        this.mergeRequests = mergeRequests;
        this.messages = messages;
        this.acceptanceCriteria = acceptanceCriteria;
        this.access = access;
        this.groupService = groupService;
        this.json = json;
    }

    /**
     * 任务中心列表：游标分页并支持 groupId/status/createdBy/repositoryId/keyword 筛选。
     * <p>
     * repositoryId 筛选在 SQL 层完成（workspace_id IN 子查询），避免只过滤当前页导致的漏数据；
     * keyword 同样在 SQL 层完成（参数化 LIKE，跨任务/需求群/创建人/绑定仓库匹配），
     * cursor 与分页基于关键词筛选后的结果集计算。
     * 一页任务的后置数据（步骤/运行/输入请求/仓库/用户/群）批量加载后内存组装。
     */
    public PagedApiResponse<TaskListItemResponse> list(UUID projectId, UUID actor, String groupId, String status,
                                                       String createdBy, String repositoryId, String keyword,
                                                       String cursor, Integer limit, String requestId) {
        access.requireProjectMember(projectId, actor);
        int size = clampLimit(limit);
        UUID cursorUuid = parseCursor(cursor);
        UUID groupUuid = parseOptionalUuid(groupId, "INVALID_GROUP_FILTER");
        UUID creatorUuid = parseOptionalUuid(createdBy, "INVALID_CREATEDBY_FILTER");
        UUID repositoryUuid = parseOptionalUuid(repositoryId, "INVALID_REPOSITORY_FILTER");
        String normalizedKeyword = normalizeKeyword(keyword);
        // 群成员可见性（契约 2026-08-17 严格收紧）：任务中心只展示用户可见群的任务
        // （主群 + 用户已加入的需求群）；显式 groupId 过滤时再按该群可见性校验。
        List<UUID> visibleGroups = groupService.visibleGroupIds(projectId, actor);
        if (groupUuid != null) {
            groupService.requireGroupMember(projectId, groupUuid, actor);
        }
        if (visibleGroups.isEmpty()) {
            return new PagedApiResponse<>(List.of(), new PageInfo(null, false), requestId);
        }
        var wrapper = Wrappers.<TaskEntity>lambdaQuery()
                .eq(TaskEntity::getProjectId, projectId)
                .in(TaskEntity::getRequirementGroupId, visibleGroups)
                .eq(groupUuid != null, TaskEntity::getRequirementGroupId, groupUuid)
                .in(!splitStatuses(status).isEmpty(), TaskEntity::getStatus, splitStatuses(status))
                .eq(creatorUuid != null, TaskEntity::getCreatedBy, creatorUuid)
                .apply(repositoryUuid != null,
                        "workspace_id in (select workspace_id from workspace_repositories where project_repository_id = {0})",
                        repositoryUuid);
        if (normalizedKeyword != null) {
            String like = likePattern(normalizedKeyword);
            wrapper.and(w -> w
                    .apply("lower(display_code) like lower({0}) escape '\\\\'", like)
                    .or().apply("lower(title) like lower({0}) escape '\\\\'", like)
                    .or().apply("lower(requirement) like lower({0}) escape '\\\\'", like)
                    .or().apply("requirement_group_id in (select id from requirement_groups where lower(name) like lower({0}) escape '\\\\')",
                            like)
                    .or().apply("created_by in (select id from users where lower(display_name) like lower({0}) escape '\\\\')",
                            like)
                    .or().apply("workspace_id in (select workspace_id from workspace_repositories where project_repository_id in "
                            + "(select id from project_repositories where lower(display_name) like lower({0}) escape '\\\\' "
                            + "or repository_id in (select id from github_repositories where lower(name) like lower({0}) escape '\\\\' "
                            + "or lower(owner_login) like lower({0}) escape '\\\\' "
                            + "or lower(concat(owner_login, '/', name)) like lower({0}) escape '\\\\')))", like));
        }
        List<TaskEntity> rows = tasks.selectList(wrapper
                .lt(cursorUuid != null, TaskEntity::getId, cursorUuid).orderByDesc(TaskEntity::getId)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<TaskEntity> page = hasMore ? rows.subList(0, size) : rows;
        List<TaskListItemResponse> items = page.isEmpty() ? List.of() : buildListItems(page);
        String next = hasMore ? items.get(items.size() - 1).getId() : null;
        return new PagedApiResponse<>(items, new PageInfo(next, hasMore), requestId);
    }

    /**
     * 任务详情：完整上下文摘要（验收标准/Workspace/操作能力/产物统计/总 Diff/来源消息）。
     */
    public TaskDetailResponse detail(UUID projectId, UUID taskId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        TaskEntity task = requireTask(projectId, taskId);
        // 群成员可见性（契约 2026-08-17 严格收紧）：任务详情仅其所属群成员可见
        if (task.getRequirementGroupId() != null) {
            groupService.requireGroupMember(projectId, task.getRequirementGroupId(), actor);
        }
        List<TaskStepEntity> stepList = steps.selectList(Wrappers.<TaskStepEntity>lambdaQuery()
                .eq(TaskStepEntity::getTaskId, taskId).orderByAsc(TaskStepEntity::getSequenceNo));
        List<TaskRunEntity> allRuns = runs.selectList(Wrappers.<TaskRunEntity>lambdaQuery()
                .eq(TaskRunEntity::getTaskId, taskId));
        Map<UUID, List<InputRequestEntity>> inputByRun = loadInputByRun(allRuns);
        WorktreeData worktreeData = loadWorktreeData(List.of(task.getWorkspaceId()));
        Map<UUID, UserEntity> userById = users
                .selectList(Wrappers.<UserEntity>lambdaQuery().in(UserEntity::getId, List.of(task.getCreatedBy())))
                .stream().collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        Map<UUID, RequirementGroupEntity> groupById = groups
                .selectList(Wrappers.<RequirementGroupEntity>lambdaQuery().in(RequirementGroupEntity::getId,
                        List.of(task.getRequirementGroupId())))
                .stream().collect(Collectors.toMap(RequirementGroupEntity::getId, Function.identity()));

        DiffReviewBatchEntity batch = latestBatch(projectId, taskId);
        List<DiffEntity> batchDiffs = batch == null ? List.of()
                : diffs.selectList(Wrappers.<DiffEntity>lambdaQuery().eq(DiffEntity::getReviewBatchId, batch.getId()));
        Attention attention = buildAttention(task, allRuns, inputByRun, batch, batchDiffs);
        ExecutionSummary execution = buildExecutionSummary(stepList, allRuns, attention != null);
        List<AcceptanceCriterion> criteria = acceptanceCriteria(
                acceptanceCriteria.selectList(Wrappers.<TaskAcceptanceCriterionEntity>lambdaQuery()
                        .eq(TaskAcceptanceCriterionEntity::getTaskId, taskId)
                        .orderByAsc(TaskAcceptanceCriterionEntity::getSequenceNo)));

        return new TaskDetailResponse(id(task.getId()), task.getDisplayCode(), id(task.getProjectId()), task.getTitle(),
                task.getRequirement(), task.getStatus(), taskStatusReason(task, allRuns), null,
                task.getDeliveryMode(), task.getDeliveryReason(),
                groupSummary(groupById.get(task.getRequirementGroupId())),
                userSummary(userById.get(task.getCreatedBy())), criteria, execution,
                attention,
                workspaceSummary(task, worktreeData), buildCapabilities(task, actor, stepList, batch),
                artifactSummary(taskId), diffReviewSummary(batch, batchDiffs), sourceMessage(task),
                id(task.getTriggerMessageId()), iso(task.getCreatedAt()), iso(task.getUpdatedAt()));
    }

    /**
     * 任务级失败原因（与诊断接口共用 {@link TaskStatusReasonFactory}，保证两链路一致）。
     * <p>
     * summary 优先使用启动/执行时持久化的 failureReason（含仓库/分支等上下文），
     * 白名单未定义的内部码也不会把真实原因降级成通用文案。
     */
    private TaskStatusReason taskStatusReason(TaskEntity task, List<TaskRunEntity> allRuns) {
        boolean hasFailedRun = allRuns != null
                && allRuns.stream().anyMatch(run -> "FAILED".equals(run.getStatus()));
        return TaskStatusReasonFactory.taskFailure(task, hasFailedRun);
    }

    /**
     * 任务执行流程步骤列表（契约 v1.8.0 §20 N01：统一 cursor envelope）。
     * 每步展示序号、标题、角色、Agent、目标仓库、依赖、状态、验收说明与最新运行；
     * 步骤数据量小，单页返回全部（hasMore=false）。
     */
    public PagedApiResponse<TaskStepListItemResponse> steps(UUID projectId, UUID taskId, UUID actor,
                                                            String requestId) {
        List<TaskStepListItemResponse> items = stepsList(projectId, taskId, actor);
        return new PagedApiResponse<>(items, new PageInfo(null, false), requestId);
    }

    private List<TaskStepListItemResponse> stepsList(UUID projectId, UUID taskId, UUID actor) {
        access.requireProjectMember(projectId, actor);
        TaskEntity task = requireTask(projectId, taskId);
        // 群成员可见性（契约 2026-08-17 严格收紧）：步骤列表仅其所属群成员可见
        if (task.getRequirementGroupId() != null) {
            groupService.requireGroupMember(projectId, task.getRequirementGroupId(), actor);
        }
        List<TaskStepEntity> stepList = withoutPlanner(steps.selectList(Wrappers.<TaskStepEntity>lambdaQuery()
                .eq(TaskStepEntity::getTaskId, taskId).orderByAsc(TaskStepEntity::getSequenceNo)));
        // 规划期仅存在 PLANNER bootstrap 步骤，过滤后为空列表（前端以 status=PLANNING 渲染规划中）
        if (stepList.isEmpty()) {
            return List.of();
        }
        List<UUID> stepIds = stepList.stream().map(TaskStepEntity::getId).toList();
        List<TaskRunEntity> allRuns = runs.selectList(Wrappers.<TaskRunEntity>lambdaQuery()
                .eq(TaskRunEntity::getTaskId, taskId));
        Map<UUID, List<TaskRunEntity>> runsByStep = allRuns.stream()
                .collect(Collectors.groupingBy(TaskRunEntity::getTaskStepId));
        Set<UUID> agentIds = stepList.stream().map(TaskStepEntity::getAssignedAgentId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 空 Map 用 emptyMap 保证 assignedAgentId 为 null 时 get(null) 返回 null（Map.of().get(null) 会抛 NPE）
        Map<UUID, AgentEntity> agentById = agentIds.isEmpty() ? Collections.emptyMap()
                : agents.selectList(Wrappers.<AgentEntity>lambdaQuery().in(AgentEntity::getId, agentIds)).stream()
                .collect(Collectors.toMap(AgentEntity::getId, Function.identity()));
        Map<UUID, List<UUID>> depsByStep = dependencies.selectByStepIds(stepIds).stream()
                .collect(Collectors.groupingBy(TaskStepDependencyEntity::getTaskStepId,
                        Collectors.mapping(TaskStepDependencyEntity::getDependsOnTaskStepId,
                                Collectors.toCollection(ArrayList::new))));
        Map<UUID, List<UUID>> scopesByStep = stepRepositories.selectByStepIds(stepIds).stream()
                .collect(Collectors.groupingBy(TaskStepRepositoryEntity::getTaskStepId,
                        Collectors.mapping(TaskStepRepositoryEntity::getProjectRepositoryId,
                                Collectors.toCollection(ArrayList::new))));
        WorktreeData worktreeData = loadWorktreeData(List.of(task.getWorkspaceId()));
        Map<UUID, WorkspaceRepositoryEntity> worktreeByRepo = worktreeData.worktrees.stream()
                .collect(Collectors.toMap(WorkspaceRepositoryEntity::getProjectRepositoryId, Function.identity()));

        List<TaskStepListItemResponse> result = new ArrayList<>(stepList.size());
        for (int index = 0; index < stepList.size(); index++) {
            TaskStepEntity step = stepList.get(index);
            result.add(toStepItem(task, step, index + 1,
                    runsByStep.getOrDefault(step.getId(), List.of()),
                    depsByStep.getOrDefault(step.getId(), List.of()),
                    scopesByStep.getOrDefault(step.getId(), List.of()),
                    agentById.get(step.getAssignedAgentId()), worktreeData, worktreeByRepo));
        }
        return result;
    }

    // ---------- 列表批量组装 ----------

    private List<TaskListItemResponse> buildListItems(List<TaskEntity> page) {
        List<UUID> taskIds = page.stream().map(TaskEntity::getId).toList();
        List<UUID> workspaceIds = page.stream().map(TaskEntity::getWorkspaceId).distinct().toList();
        List<UUID> groupIds = page.stream().map(TaskEntity::getRequirementGroupId).distinct().toList();
        List<UUID> creatorIds = page.stream().map(TaskEntity::getCreatedBy).distinct().toList();

        Map<UUID, List<TaskStepEntity>> stepsByTask = steps
                .selectList(Wrappers.<TaskStepEntity>lambdaQuery().in(TaskStepEntity::getTaskId, taskIds)).stream()
                .collect(Collectors.groupingBy(TaskStepEntity::getTaskId));
        List<TaskRunEntity> allRuns = runs.selectList(Wrappers.<TaskRunEntity>lambdaQuery()
                .in(TaskRunEntity::getTaskId, taskIds));
        Map<UUID, List<TaskRunEntity>> runsByTask = allRuns.stream()
                .collect(Collectors.groupingBy(TaskRunEntity::getTaskId));
        Map<UUID, List<InputRequestEntity>> inputByRun = loadInputByRun(allRuns);
        WorktreeData worktreeData = loadWorktreeData(workspaceIds);
        Map<UUID, List<WorkspaceRepositoryEntity>> worktreesByTask = worktreeData.worktrees.stream()
                .collect(Collectors.groupingBy(WorkspaceRepositoryEntity::getWorkspaceId));
        Map<UUID, UserEntity> userById = users
                .selectList(Wrappers.<UserEntity>lambdaQuery().in(UserEntity::getId, creatorIds)).stream()
                .collect(Collectors.toMap(UserEntity::getId, Function.identity()));
        Map<UUID, RequirementGroupEntity> groupById = groups
                .selectList(Wrappers.<RequirementGroupEntity>lambdaQuery().in(RequirementGroupEntity::getId, groupIds))
                .stream().collect(Collectors.toMap(RequirementGroupEntity::getId, Function.identity()));
        Map<UUID, DiffReviewBatchEntity> batchByTask = taskIds.isEmpty() ? Collections.emptyMap() : diffBatches
                .selectList(Wrappers.<DiffReviewBatchEntity>lambdaQuery().in(DiffReviewBatchEntity::getTaskId, taskIds))
                .stream().collect(Collectors.toMap(DiffReviewBatchEntity::getTaskId, Function.identity()));
        Set<UUID> batchIds = batchByTask.values().stream().map(DiffReviewBatchEntity::getId).collect(Collectors.toSet());
        Map<UUID, List<DiffEntity>> diffsByBatch = batchIds.isEmpty() ? Collections.emptyMap() : diffs
                .selectList(Wrappers.<DiffEntity>lambdaQuery().in(DiffEntity::getReviewBatchId, batchIds)).stream()
                .collect(Collectors.groupingBy(DiffEntity::getReviewBatchId));

        return page.stream().map(task -> toListItem(task,
                        stepsByTask.getOrDefault(task.getId(), List.of()),
                        runsByTask.getOrDefault(task.getId(), List.of()), inputByRun,
                        worktreesByTask.getOrDefault(task.getWorkspaceId(), List.of()), worktreeData, userById, groupById,
                        batchByTask.get(task.getId()), diffsByBatch))
                .toList();
    }

    private TaskListItemResponse toListItem(TaskEntity task, List<TaskStepEntity> stepList, List<TaskRunEntity> taskRuns,
                                            Map<UUID, List<InputRequestEntity>> inputByRun, List<WorkspaceRepositoryEntity> worktreeList,
                                            WorktreeData worktreeData, Map<UUID, UserEntity> userById,
                                            Map<UUID, RequirementGroupEntity> groupById,
                                            DiffReviewBatchEntity batch, Map<UUID, List<DiffEntity>> diffsByBatch) {
        Attention attention = buildAttention(task, taskRuns, inputByRun, batch,
                batch == null ? List.of() : diffsByBatch.getOrDefault(batch.getId(), List.of()));
        ExecutionSummary execution = buildExecutionSummary(stepList, taskRuns, attention != null);
        List<RepositorySummary> repositories = worktreeList.stream()
                .map(w -> repositorySummary(w, worktreeData.bindingById.get(w.getProjectRepositoryId()),
                        worktreeData.repoById.get(bindingRepositoryId(worktreeData, w.getProjectRepositoryId()))))
                .toList();
        return new TaskListItemResponse(id(task.getId()), task.getDisplayCode(), id(task.getProjectId()),
                task.getTitle(), requirementSummary(task.getRequirement()), task.getStatus(), null,
                task.getDeliveryMode(), task.getDeliveryReason(), groupSummary(groupById.get(task.getRequirementGroupId())),
                userSummary(userById.get(task.getCreatedBy())), repositories, execution, attention,
                iso(task.getCreatedAt()), iso(task.getUpdatedAt()));
    }

    // ---------- 执行统计 / 待处理事项 / 操作能力 ----------

    /**
     * 过滤 PLANNER bootstrap 步骤：PLANNER 步骤会在编排中产生 PLANNER TaskRun（供失败诊断与
     * 重试审计），但不进正式执行图，也不出现在步骤列表 / 执行统计 / 能力派生中（规划期 tasks
     * 状态为 PLANNING，正式步骤尚未生成）。Planner Run 仍可通过任务运行列表查看。
     */
    private List<TaskStepEntity> withoutPlanner(List<TaskStepEntity> stepList) {
        return stepList.stream().filter(step -> !"PLANNER".equals(step.getRole())).toList();
    }

    /**
     * 由步骤真实状态与最新运行状态聚合执行统计；waiting/blocked 取步骤最新运行状态。
     */
    private ExecutionSummary buildExecutionSummary(List<TaskStepEntity> stepList, List<TaskRunEntity> taskRuns,
                                                   boolean requiresUserAction) {
        // PLANNER bootstrap 步骤不属于正式执行步骤，不计入执行统计
        stepList = withoutPlanner(stepList);
        Map<UUID, List<TaskRunEntity>> runsByStep = taskRuns.stream()
                .collect(Collectors.groupingBy(TaskRunEntity::getTaskStepId));
        int pending = 0, running = 0, succeeded = 0, failed = 0, waiting = 0, blocked = 0;
        TaskStepEntity active = null;
        List<TaskStepEntity> ordered = stepList.stream()
                .sorted(Comparator.comparing(TaskStepEntity::getSequenceNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        for (TaskStepEntity step : ordered) {
            String stepStatus = step.getStatus();
            if ("PENDING".equals(stepStatus)) {
                pending++;
            } else if ("RUNNING".equals(stepStatus)) {
                running++;
                if (active == null) {
                    active = step;
                }
            } else if ("SUCCEEDED".equals(stepStatus)) {
                succeeded++;
            } else if ("FAILED".equals(stepStatus)) {
                failed++;
            }
            TaskRunEntity latest = latestRun(runsByStep.getOrDefault(step.getId(), List.of()));
            if (latest != null) {
                if (RUN_WAITING.contains(latest.getStatus())) {
                    waiting++;
                    if (active == null && !"RUNNING".equals(stepStatus)) {
                        active = step;
                    }
                } else if ("BLOCKED".equals(latest.getStatus())) {
                    blocked++;
                    if (active == null) {
                        active = step;
                    }
                }
            }
        }
        if (active == null && !ordered.isEmpty()) {
            active = ordered.get(ordered.size() - 1);
        }
        return new ExecutionSummary(ordered.size(), pending, running, waiting, blocked, succeeded, failed,
                active == null ? null : active.getRole(), active == null ? null : active.getTitle(),
                requiresUserAction);
    }

    /**
     * 按任务状态与最新运行推导待处理事项；无待处理事项返回 null。
     * batch/batchDiffs 用于 Diff 审核与交付提示的关联跳转 ID。
     */
    private Attention buildAttention(TaskEntity task, List<TaskRunEntity> taskRuns,
                                     Map<UUID, List<InputRequestEntity>> inputByRun,
                                     DiffReviewBatchEntity batch, List<DiffEntity> batchDiffs) {
        String status = task.getStatus();
        if ("WAITING_DIFF_CONFIRMATION".equals(status)) {
            if (batch != null && "SUPERSEDED".equals(batch.getReviewStatus())) {
                return new Attention("DIFF_REVIEW_SUPERSEDED", "Diff 已被后续修改取代",
                        "请查看同一 Workspace 的最新 Diff，旧 Diff 不能再确认", null, null,
                        id(batch.getId()), null, iso(task.getUpdatedAt()));
            }
            // 仅 DIFF_FIRST + USER 确认的批次才需要用户确认；MR_FIRST 为系统自动授权
            // （ACCEPTED+SYSTEM），不应让前端误以为等待用户确认。
            boolean userConfirmation = "DIFF_FIRST".equals(task.getDeliveryMode())
                    && batch != null
                    && "PENDING_CONFIRMATION".equals(batch.getReviewStatus())
                    && "USER".equals(batch.getConfirmationSource());
            if (!userConfirmation) {
                return new Attention("DELIVERING", "自动交付中", "MR_FIRST 已自动授权交付，无需用户确认",
                        null, null, id(batch == null ? null : batch.getId()), null, iso(task.getUpdatedAt()));
            }
            return new Attention("DIFF_CONFIRMATION_REQUIRED", "等待确认最终 Diff", "已生成多仓库总 Diff，等待确认",
                    null, null, id(batch == null ? null : batch.getId()), null, iso(task.getUpdatedAt()));
        }
        if ("WAITING_PREFLIGHT".equals(status)) {
            return new Attention("PREFLIGHT_REQUIRED", "等待 MR 前预检",
                    "代码已推送；请完成 Dry Run 和独立成员 CQ+1 后创建 MR",
                    null, null, id(batch == null ? null : batch.getId()), null, iso(task.getUpdatedAt()));
        }
        if ("DIFF_REJECTED".equals(status)) {
            return new Attention("DIFF_REJECTED", "Diff 已拒绝", "Workspace 修改已保留，可回复 Diff 创建续作任务",
                    null, null, id(batch == null ? null : batch.getId()), null, iso(task.getUpdatedAt()));
        }
        if ("DELIVERY_FAILED".equals(status)) {
            UUID failedRepo = batchDiffs == null ? null : batchDiffs.stream()
                    .filter(d -> "FAILED".equals(d.getDeliveryStatus()))
                    .map(DiffEntity::getProjectRepositoryId).filter(Objects::nonNull)
                    .findFirst().orElse(null);
            return new Attention("DELIVERY_FAILED", "交付失败", "部分或全部仓库交付失败，可查看详情后重试",
                    null, null, id(batch == null ? null : batch.getId()), id(failedRepo), iso(task.getUpdatedAt()));
        }
        List<TaskRunEntity> desc = taskRuns.stream().sorted(latestFirst()).toList();
        for (TaskRunEntity run : desc) {
            String runStatus = run.getStatus();
            List<InputRequestEntity> requests = inputByRun.getOrDefault(run.getId(), List.of());
            if ("WAITING_INPUT".equals(runStatus)) {
                InputRequestEntity req = firstPending(requests, "INPUT");
                return new Attention("INPUT_REQUIRED", "等待用户输入",
                        req == null ? "等待用户补充输入" : req.getPrompt(), id(run.getId()),
                        req == null ? null : id(req.getId()), null, null,
                        iso(req == null ? run.getUpdatedAt() : req.getCreatedAt()));
            }
            if ("WAITING_APPROVAL".equals(runStatus)) {
                InputRequestEntity req = firstPending(requests, "APPROVAL");
                return new Attention("APPROVAL_REQUIRED", "等待审批",
                        req == null ? "等待审批确认" : req.getPrompt(), id(run.getId()),
                        req == null ? null : id(req.getId()), null, null,
                        iso(req == null ? run.getUpdatedAt() : req.getCreatedAt()));
            }
            if ("BLOCKED".equals(runStatus)) {
                InputRequestEntity req = latestRequest(requests, "APPROVAL");
                String reason = req == null ? null : req.getReason();
                return new Attention("BLOCKED", "执行被阻塞",
                        reason == null || reason.isBlank() ? "执行流程被阻塞，等待处理" : reason, id(run.getId()),
                        req == null ? null : id(req.getId()), null, null, iso(run.getUpdatedAt()));
            }
        }
        TaskRunEntity failedRun = taskRuns.stream()
                .filter(r -> "FAILED".equals(r.getStatus()))
                .max(Comparator.comparing(TaskRunEntity::getFailureOccurredAt,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(TaskRunEntity::getUpdatedAt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(TaskRunEntity::getId,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
        // FAILED 也是需要展示失败原因的终态；成功、取消等终态不应被历史失败运行污染。
        if (failedRun != null && ("FAILED".equals(status) || !TERMINAL_TASK_STATUSES.contains(status))) {
            String publicCode = ExecutionContentSanitizer.publicFailureCode(failedRun.getFailureCode());
            // 历史 failureReason 可能是模型/供应商异常原文；任务列表同样只能展示受控文案。
            String summary = ExecutionContentSanitizer.userFailureDescription(publicCode);
            return new Attention("EXECUTION_FAILED", "执行失败", summary,
                    id(failedRun.getId()), null, null, null,
                    iso(failedRun.getFailureOccurredAt() == null
                            ? failedRun.getUpdatedAt() : failedRun.getFailureOccurredAt()));
        }
        return null;
    }

    /**
     * 当前用户对当前任务的操作能力，由任务状态与调用者身份派生。
     */
    private TaskCapabilities buildCapabilities(TaskEntity task, UUID actor, List<TaskStepEntity> stepList,
                                               DiffReviewBatchEntity batch) {
        // PLANNER bootstrap 步骤不参与"待执行步骤"能力派生，避免规划期误开"可替换"
        stepList = withoutPlanner(stepList);
        boolean ownerOrAdmin = access.isOwnerOrAdmin(task.getCreatedBy(), task.getProjectId(), actor);
        String status = task.getStatus();

        boolean cancellable = ownerOrAdmin && CANCELLABLE_TASK_STATUSES.contains(status);
        String cancelReason = cancellable ? null
                : (!ownerOrAdmin ? "TASK_FORBIDDEN" : "TASK_NOT_CANCELLABLE");

        boolean hasPendingStep = stepList.stream().anyMatch(s -> "PENDING".equals(s.getStatus()));
        boolean replaceable = ownerOrAdmin && !TERMINAL_TASK_STATUSES.contains(status) && hasPendingStep;
        String replaceReason = replaceable ? null
                : (!ownerOrAdmin ? "TASK_FORBIDDEN"
                   : (!hasPendingStep ? "NO_PENDING_STEP" : "TASK_TERMINATED"));

        // 仅 DIFF_FIRST + PENDING_CONFIRMATION + USER（用户发起确认）的批次可确认/拒绝；
        // MR_FIRST 为系统自动授权（ACCEPTED+SYSTEM），不存在用户确认环节。
        boolean reviewDecidable = "DIFF_FIRST".equals(task.getDeliveryMode())
                && batch != null
                && "PENDING_CONFIRMATION".equals(batch.getReviewStatus())
                && "USER".equals(batch.getConfirmationSource());
        boolean canConfirm = ownerOrAdmin && reviewDecidable;
        String confirmReason = canConfirm ? null
                : (!ownerOrAdmin ? "DIFF_REVIEW_FORBIDDEN"
                   : (batch == null ? "DIFF_REVIEW_NOT_FOUND"
                   : ("SUPERSEDED".equals(batch.getReviewStatus()) ? "DIFF_REVIEW_SUPERSEDED"
                   : "DIFF_REVIEW_NOT_DECIDABLE")));
        boolean canReject = canConfirm;
        String rejectReason = confirmReason;

        boolean retryableDelivery = batch != null && "ACCEPTED".equals(batch.getReviewStatus())
                && ("PARTIALLY_DELIVERED".equals(batch.getDeliveryStatus())
                || "FAILED".equals(batch.getDeliveryStatus()));
        boolean canRetry = ownerOrAdmin && retryableDelivery;
        String retryReason = canRetry ? null
                : (!ownerOrAdmin ? "DIFF_REVIEW_FORBIDDEN"
                   : (batch == null ? "DIFF_REVIEW_NOT_FOUND" : "DIFF_DELIVERY_NOT_RETRYABLE"));

        return new TaskCapabilities(cancellable, cancelReason, replaceable, replaceReason, canConfirm, confirmReason,
                canReject, rejectReason, canRetry, retryReason);
    }

    // ---------- 仓库 / 用户 / 群 / 产物 / Diff / 消息 摘要 ----------

    private RepositorySummary repositorySummary(WorkspaceRepositoryEntity worktree, ProjectRepositoryEntity binding,
                                                GitHubRepositoryEntity repo) {
        String name = binding != null && binding.getDisplayName() != null && !binding.getDisplayName().isBlank()
                ? binding.getDisplayName()
                : (repo == null ? null : repo.getName());
        String fullName = repo == null ? null : repo.getOwnerLogin() + "/" + repo.getName();
        String defaultBranch = binding == null ? null : binding.getDefaultBranch();
        return new RepositorySummary(
                worktree == null ? (binding == null ? null : id(binding.getId())) : id(worktree.getProjectRepositoryId()),
                name, fullName, "GITHUB", defaultBranch, worktree == null ? defaultBranch : worktree.getBaseRef(),
                worktree == null ? null : worktree.getBaseCommit(),
                worktree == null ? null : worktree.getSourceBranch(),
                worktree == null ? null : worktree.getHeadCommit());
    }

    private WorkspaceSummary workspaceSummary(TaskEntity task, WorktreeData data) {
        WorkspaceEntity workspace = workspaces.selectById(task.getWorkspaceId());
        List<RepositorySummary> repos = data.worktrees.stream()
                .map(w -> repositorySummary(w, data.bindingById.get(w.getProjectRepositoryId()),
                        data.repoById.get(bindingRepositoryId(data, w.getProjectRepositoryId()))))
                .toList();
        return new WorkspaceSummary(id(task.getWorkspaceId()), workspace == null ? null : workspace.getStatus(), repos);
    }

    private ArtifactSummary artifactSummary(UUID taskId) {
        List<TaskExecutionArtifactEntity> list = artifacts.selectList(Wrappers.<TaskExecutionArtifactEntity>lambdaQuery()
                .eq(TaskExecutionArtifactEntity::getTaskId, taskId));
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (TaskExecutionArtifactEntity artifact : list) {
            byType.merge(artifact.getArtifactType(), 1, Integer::sum);
        }
        return new ArtifactSummary(list.size(), byType);
    }

    private DiffReviewSummary diffReviewSummary(DiffReviewBatchEntity batch, List<DiffEntity> values) {
        if (batch == null) {
            return new DiffReviewSummary(false, null, null, null, 0, 0, 0, 0);
        }
        int files = 0, additions = 0, deletions = 0;
        for (DiffEntity diff : values) {
            Map<String, Object> stats = diff.getChangeStats();
            if (stats != null) {
                files += intValue(stats.get("files"));
                additions += intValue(stats.get("additions"));
                deletions += intValue(stats.get("deletions"));
            }
        }
        String firstDiffId = values.stream()
                .sorted(Comparator.comparing(DiffEntity::getProjectRepositoryId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(DiffEntity::getId).map(this::id).findFirst().orElse(null);
        return new DiffReviewSummary(true, firstDiffId, batch.getReviewStatus(), batch.getDeliveryStatus(),
                values.size(), files, additions, deletions);
    }

    private List<AcceptanceCriterion> acceptanceCriteria(List<TaskAcceptanceCriterionEntity> list) {
        return list.stream()
                .map(c -> new AcceptanceCriterion(id(c.getId()), c.getTitle(), c.getDescription(),
                        c.getStatus() == null ? "PENDING" : c.getStatus()))
                .toList();
    }

    private SourceMessage sourceMessage(TaskEntity task) {
        if (task.getTriggerMessageId() == null) {
            return null;
        }
        MessageEntity message = messages.selectById(task.getTriggerMessageId());
        if (message == null) {
            return null;
        }
        UserSummary sender;
        if (message.getAuthorUserId() != null) {
            UserEntity user = users.selectById(message.getAuthorUserId());
            sender = user == null ? new UserSummary(id(message.getAuthorUserId()), "已注销用户", null)
                    : userSummary(user);
        } else if (message.getAgentId() != null) {
            AgentEntity agent = agents.selectById(message.getAgentId());
            sender = agent == null ? new UserSummary(id(message.getAgentId()), null, null)
                    : new UserSummary(id(agent.getId()), agent.getName(), agent.getAvatar());
        } else {
            sender = new UserSummary(null, "系统", null);
        }
        return new SourceMessage(id(message.getId()), sender, textExcerpt(message), iso(message.getCreatedAt()));
    }

    /**
     * 从消息 content JSON 提取纯文本摘要，截断到 200 字符，解析失败返回 null。
     */
    private String textExcerpt(MessageEntity message) {
        try {
            Map<String, Object> content = json.readValue(message.getContent(), new TypeReference<Map<String, Object>>() {
            });
            Object text = content.get("text");
            if (!(text instanceof String raw)) {
                return null;
            }
            String value = raw.strip();
            return value.length() <= TEXT_EXCERPT_LIMIT ? value : value.substring(0, TEXT_EXCERPT_LIMIT) + "...";
        } catch (Exception e) {
            return null;
        }
    }

    private UserSummary userSummary(UserEntity user) {
        if (user == null) {
            return null;
        }
        String name = user.getDisplayName();
        if (name == null || name.isBlank()) {
            name = "已注销用户";
        }
        return new UserSummary(id(user.getId()), name, user.getAvatarUrl());
    }

    private RequirementGroupSummary groupSummary(RequirementGroupEntity group) {
        if (group == null) {
            return null;
        }
        return new RequirementGroupSummary(id(group.getId()), group.getName(), group.getStatus());
    }

    private AgentSummary agentSummary(AgentEntity agent) {
        if (agent == null) {
            return null;
        }
        return new AgentSummary(id(agent.getId()), agent.getName(), agent.getRole(), agent.getAvatar(),
                agent.getStatus());
    }

    // ---------- 步骤组装 ----------

    private TaskStepListItemResponse toStepItem(TaskEntity task, TaskStepEntity step, int displaySequence,
                                                List<TaskRunEntity> stepRuns,
                                                List<UUID> dependencyIds, List<UUID> scopedRepoIds, AgentEntity agent, WorktreeData worktreeData,
                                                Map<UUID, WorkspaceRepositoryEntity> worktreeByRepo) {
        TaskRunEntity latest = latestRun(stepRuns);
        TaskStepLatestRun latestRun = latest == null ? null
                : new TaskStepLatestRun(id(latest.getId()), latest.getStatus(), statusSummary(latest.getStatus()),
                iso(latest.getStartedAt()), iso(latest.getFinishedAt()), durationMs(latest));
        RepositorySummary repository = stepRepository(step, scopedRepoIds, worktreeData, worktreeByRepo);
        LocalDateTime startedAt = stepRuns.stream().map(TaskRunEntity::getStartedAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        LocalDateTime finishedAt = stepRuns.stream().map(TaskRunEntity::getFinishedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return new TaskStepListItemResponse(id(step.getId()), id(task.getId()), displaySequence, step.getTitle(),
                step.getInstruction(), step.getRole(), step.getRequiredCapabilities(), agentSummary(agent), repository,
                dependencyIds.stream().map(this::id).toList(), step.getStatus(), step.getAcceptanceCriteria(),
                latestRun, stepRuns.size(), iso(startedAt), iso(finishedAt), iso(step.getCreatedAt()),
                iso(step.getUpdatedAt()));
    }

    /**
     * 步骤目标仓库：取步骤仓库范围第一个，无范围则取任务 Workspace 第一个 worktree。
     */
    private RepositorySummary stepRepository(TaskStepEntity step, List<UUID> scopedRepoIds, WorktreeData worktreeData,
                                             Map<UUID, WorkspaceRepositoryEntity> worktreeByRepo) {
        UUID repositoryId = scopedRepoIds.isEmpty() ? worktreeData.worktrees.stream()
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId).findFirst().orElse(null) : scopedRepoIds.get(0);
        if (repositoryId == null) {
            return null;
        }
        WorkspaceRepositoryEntity worktree = worktreeByRepo.get(repositoryId);
        if (worktree == null && scopedRepoIds.isEmpty()) {
            return null;
        }
        if (worktree == null) {
            worktree = new WorkspaceRepositoryEntity();
            worktree.setProjectRepositoryId(repositoryId);
        }
        return repositorySummary(worktree, worktreeData.bindingById.get(repositoryId),
                worktreeData.repoById.get(bindingRepositoryId(worktreeData, repositoryId)));
    }

    // ---------- 数据加载辅助 ----------

    /**
     * 一次性加载多个 Workspace 的 worktree 与仓库绑定/镜像数据。
     */
    private WorktreeData loadWorktreeData(List<UUID> workspaceIds) {
        List<WorkspaceRepositoryEntity> worktreeList = worktrees.selectByWorkspaces(workspaceIds);
        List<UUID> bindingIds = worktreeList.stream().map(WorkspaceRepositoryEntity::getProjectRepositoryId).distinct()
                .toList();
        Map<UUID, ProjectRepositoryEntity> bindingById = bindingIds.isEmpty() ? Collections.emptyMap()
                : projectRepositories
                .selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery()
                        .in(ProjectRepositoryEntity::getId, bindingIds))
                .stream().collect(Collectors.toMap(ProjectRepositoryEntity::getId, Function.identity()));
        List<UUID> githubIds = bindingById.values().stream().map(ProjectRepositoryEntity::getRepositoryId).distinct()
                .toList();
        Map<UUID, GitHubRepositoryEntity> repoById = githubIds.isEmpty() ? Collections.emptyMap()
                : githubRepositories
                .selectList(Wrappers.<GitHubRepositoryEntity>lambdaQuery()
                        .in(GitHubRepositoryEntity::getId, githubIds))
                .stream().collect(Collectors.toMap(GitHubRepositoryEntity::getId, Function.identity()));
        return new WorktreeData(worktreeList, bindingById, repoById);
    }

    private Map<UUID, List<InputRequestEntity>> loadInputByRun(List<TaskRunEntity> taskRuns) {
        List<UUID> runIds = taskRuns.stream().map(TaskRunEntity::getId).toList();
        if (runIds.isEmpty()) {
            return Map.of();
        }
        return inputRequests
                .selectList(Wrappers.<InputRequestEntity>lambdaQuery().in(InputRequestEntity::getTaskRunId, runIds))
                .stream().collect(Collectors.groupingBy(InputRequestEntity::getTaskRunId));
    }

    private DiffReviewBatchEntity latestBatch(UUID projectId, UUID taskId) {
        List<DiffReviewBatchEntity> list = diffBatches.selectList(Wrappers.<DiffReviewBatchEntity>lambdaQuery()
                .eq(DiffReviewBatchEntity::getProjectId, projectId).eq(DiffReviewBatchEntity::getTaskId, taskId)
                .orderByDesc(DiffReviewBatchEntity::getCreatedAt).last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    private UUID bindingRepositoryId(WorktreeData data, UUID bindingId) {
        ProjectRepositoryEntity binding = data.bindingById.get(bindingId);
        return binding == null ? null : binding.getRepositoryId();
    }

    // ---------- 简单工具 ----------

    private TaskRunEntity latestRun(List<TaskRunEntity> stepRuns) {
        return stepRuns.stream().max(Comparator.comparing(TaskRunEntity::getId)).orElse(null);
    }

    private Comparator<TaskRunEntity> latestFirst() {
        return Comparator.comparing(TaskRunEntity::getId).reversed();
    }

    private InputRequestEntity firstPending(List<InputRequestEntity> requests, String kind) {
        return requests.stream()
                .filter(r -> "PENDING".equals(r.getStatus()) && kind.equals(r.getKind()))
                .max(Comparator.comparing(InputRequestEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private InputRequestEntity latestRequest(List<InputRequestEntity> requests, String kind) {
        return requests.stream().filter(r -> kind.equals(r.getKind()))
                .max(Comparator.comparing(InputRequestEntity::getCreatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    private Long durationMs(TaskRunEntity run) {
        if (run.getStartedAt() == null || run.getFinishedAt() == null
                || run.getFinishedAt().isBefore(run.getStartedAt())) {
            return null;
        }
        return Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis();
    }

    /** 脱敏状态文案与 TaskRun 详情保持一致。 */
    private String statusSummary(String status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case "QUEUED" -> "等待执行";
            case "RUNNING" -> "执行中";
            case "SUCCEEDED" -> "执行成功";
            case "FAILED" -> "执行失败";
            case "WAITING_INPUT" -> "等待用户补充输入";
            case "WAITING_APPROVAL" -> "等待审批确认";
            case "BLOCKED" -> "执行被阻塞";
            case "CANCELLING" -> "正在取消";
            case "CANCELLED" -> "已取消";
            default -> null;
        };
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private String requirementSummary(String requirement) {
        if (requirement == null) {
            return null;
        }
        String value = requirement.strip();
        return value.length() <= TEXT_EXCERPT_LIMIT ? value : value.substring(0, TEXT_EXCERPT_LIMIT) + "...";
    }

    private TaskEntity requireTask(UUID projectId, UUID taskId) {
        TaskEntity task = tasks.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或无权访问");
        }
        return task;
    }

    /**
     * 解析状态过滤参数：支持逗号分隔多值（如 status=SUCCEEDED,FAILED,CANCELLED），
     * 空值返回空列表（不过滤）。
     */
    private List<String> splitStatuses(String status) {
        if (status == null || status.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(status.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    private UUID parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(cursor);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "游标格式不合法");
        }
    }

    private UUID parseOptionalUuid(String value, String errorCode) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, errorCode, "筛选参数格式不合法");
        }
    }

    /**
     * 规范化 keyword：去除首尾空白；空白串等同于未传；超过 100 个 Unicode 字符返回 422。
     */
    private String normalizeKeyword(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) > 100) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_QUERY_PARAMETER",
                    "keyword must be 100 characters or fewer");
        }
        return trimmed;
    }

    /**
     * 构造参数化 LIKE 模式：小写化并把 LIKE 通配符与转义符转义为字面量，
     * 配合 SQL 侧 {@code escape '\'} 使用，防止用户输入 %/_ 被当作通配符。
     */
    private String likePattern(String keyword) {
        return "%" + keyword.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    private int clampLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String iso(LocalDateTime time) {
        return time == null ? null : time.atOffset(ZoneOffset.UTC).toInstant().toString();
    }

    private String id(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    /**
         * Workspace worktree 与仓库绑定/镜像的批量加载结果。
         */
        private record WorktreeData(List<WorkspaceRepositoryEntity> worktrees,
                                    Map<UUID, ProjectRepositoryEntity> bindingById,
                                    Map<UUID, GitHubRepositoryEntity> repoById) {
    }
}
