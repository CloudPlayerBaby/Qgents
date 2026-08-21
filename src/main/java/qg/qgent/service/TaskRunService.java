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
import qg.qgent.orchestration.llm.LlmObservation;
import qg.qgent.orchestration.worker.WorkerToolExecution;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.orchestration.ExecutionContentSanitizer;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Controlled execution-attempt service for TaskSteps.
 * 提供运行记录查询、重试、取消与人机输入/审批处理；所有查询先校验路径 projectId 下资源归属，
 * 写操作按状态机推进并发布项目级事件。
 * 状态机：retry 仅接受 FAILED/CANCELLED/BLOCKED；cancel 对 QUEUED 直接置 CANCELLED，
 * 对 RUNNING/WAITING_INPUT/WAITING_APPROVAL/BLOCKED 置 CANCELLING；reply 后恢复
 * RUNNING，reject 后进入 BLOCKED。
 * 真实执行、Sandbox 生命周期与日志/步骤写入由受控执行服务（TODO 接缝）驱动，本服务只做受理与查询。
 */
@Service
public class TaskRunService {
    private static final Set<String> RETRYABLE = Set.of("FAILED", "CANCELLED", "BLOCKED");
    /**
     * 允许续跑的任务状态：仅终态/未启动（FAILED/CANCELLED 等）与尚未进入交付的任务可重试；
     * RUNNING/WAITING_DIFF_CONFIRMATION/WAITING_PREFLIGHT/DIFF_REJECTED/DELIVERING/SUCCEEDED/DELIVERY_FAILED 不接受外部续跑，
     * 避免与进行中的编排或已交付的代码冲突。
     */
    private static final Set<String> RESUMABLE_TASK_STATUSES = Set.of("PLANNING", "PENDING", "FAILED", "CANCELLED");
    private static final Set<String> CANCELLABLE_RUNNING = Set.of("RUNNING", "WAITING_INPUT", "WAITING_APPROVAL",
            "BLOCKED");
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final TaskRunMapper taskRunMapper;
    private final ExecutionLogMapper logMapper;
    private final InputRequestMapper inputRequestMapper;
    private final DiffMapper diffMapper;
    private final TaskStepMapper taskStepMapper;
    private final AgentMapper agentMapper;
    private final TaskExecutionArtifactMapper artifactMapper;
    private final TaskMapper taskMapper;
    private final RequirementGroupMapper requirementGroupMapper;
    private final ProjectRepositoryMapper projectRepositoryMapper;
    private final WorkspaceRepositoryMapper workspaceRepositoryMapper;
    private final ProjectAccessService projectAccess;
    private final GroupService groupService;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final TaskRunLogService taskRunLogService;
    private final TaskRunWorkerExecutionMapper workerExecutionMapper;

    public TaskRunService(TaskRunMapper taskRunMapper,
                          ExecutionLogMapper logMapper, InputRequestMapper inputRequestMapper, DiffMapper diffMapper,
                          TaskStepMapper taskStepMapper, AgentMapper agentMapper, TaskExecutionArtifactMapper artifactMapper,
                          TaskMapper taskMapper, RequirementGroupMapper requirementGroupMapper,
                          ProjectRepositoryMapper projectRepositoryMapper, WorkspaceRepositoryMapper workspaceRepositoryMapper,
                          ProjectAccessService projectAccess, GroupService groupService, EventService eventService,
                          NotificationService notificationService,
                          org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this(taskRunMapper, logMapper, inputRequestMapper, diffMapper, taskStepMapper, agentMapper, artifactMapper,
                taskMapper, requirementGroupMapper, projectRepositoryMapper, workspaceRepositoryMapper, projectAccess,
                groupService, eventService, notificationService, eventPublisher,
                new TaskRunLogService(logMapper, taskMapper, taskRunMapper, eventService), null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskRunService(TaskRunMapper taskRunMapper,
                          ExecutionLogMapper logMapper, InputRequestMapper inputRequestMapper, DiffMapper diffMapper,
                          TaskStepMapper taskStepMapper, AgentMapper agentMapper, TaskExecutionArtifactMapper artifactMapper,
                          TaskMapper taskMapper, RequirementGroupMapper requirementGroupMapper,
                          ProjectRepositoryMapper projectRepositoryMapper, WorkspaceRepositoryMapper workspaceRepositoryMapper,
                          ProjectAccessService projectAccess, GroupService groupService, EventService eventService,
                          NotificationService notificationService,
                          org.springframework.context.ApplicationEventPublisher eventPublisher,
                          TaskRunLogService taskRunLogService,
                          TaskRunWorkerExecutionMapper workerExecutionMapper) {
        this.taskRunMapper = taskRunMapper;
        this.logMapper = logMapper;
        this.inputRequestMapper = inputRequestMapper;
        this.diffMapper = diffMapper;
        this.taskStepMapper = taskStepMapper;
        this.agentMapper = agentMapper;
        this.artifactMapper = artifactMapper;
        this.taskMapper = taskMapper;
        this.requirementGroupMapper = requirementGroupMapper;
        this.projectRepositoryMapper = projectRepositoryMapper;
        this.workspaceRepositoryMapper = workspaceRepositoryMapper;
        this.projectAccess = projectAccess;
        this.groupService = groupService;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.eventPublisher = eventPublisher;
        this.taskRunLogService = taskRunLogService;
        this.workerExecutionMapper = workerExecutionMapper;
    }

    /**
     * Lists immutable execution attempts belonging to the confirmed top-level task.
     * 列表项补充可读摘要（步骤标题、Agent、状态摘要、等待/失败原因与执行时间），
     * 步骤与 Agent 一次性批量加载，避免逐条运行查询。
     */
    public ApiPageResponse<TaskRunListItemResponse> listByTask(UUID projectId, UUID taskId, UUID userId,
                                                               String cursor, int limit, String requestId) {
        projectAccess.requireProjectMember(projectId, userId);
        requireTaskVisible(projectId, taskId, userId);
        int size = clampLimit(limit);
        UUID cursorUuid = parseCursor(cursor);
        List<TaskRunEntity> rows = taskRunMapper.selectList(Wrappers.<TaskRunEntity>lambdaQuery()
                .eq(TaskRunEntity::getProjectId, projectId).eq(TaskRunEntity::getTaskId, taskId)
                .lt(cursorUuid != null, TaskRunEntity::getId, cursorUuid).orderByDesc(TaskRunEntity::getId)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<TaskRunEntity> page = hasMore ? rows.subList(0, size) : rows;
        List<TaskRunListItemResponse> items = page.isEmpty() ? List.of() : buildListItems(page);
        String next = hasMore ? items.get(items.size() - 1).getId() : null;
        return new ApiPageResponse<>(items, new PageMeta(next, hasMore), requestId);
    }

    /**
     * Returns one run with its owning TaskStep and Task-level result summary.
     * 详情额外返回等待/阻塞/失败原因 statusReason。
     */
    public TaskRunDetailResponse detail(UUID projectId, UUID taskRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskRunEntity run = requireRun(projectId, taskRunId);
        requireTaskVisible(projectId, run.getTaskId(), userId);
        TaskStepEntity step = run.getTaskStepId() == null ? null : taskStepMapper.selectById(run.getTaskStepId());
        TaskExecutionArtifactEntity latestArtifact = latestRunArtifact(run.getId());
        AgentEntity agent = run.getAgentId() == null ? null : agentMapper.selectById(run.getAgentId());
        List<InputRequestEntity> requests = inputRequestMapper.selectList(
                Wrappers.<InputRequestEntity>lambdaQuery().eq(InputRequestEntity::getTaskRunId, run.getId()));
        return new TaskRunDetailResponse(
                id(run.getId()), id(run.getProjectId()), id(run.getTaskId()), id(run.getTaskStepId()),
                step == null ? null : step.getTitle(), id(run.getAgentId()), agentSummary(agent),
                run.getRole(), run.getStatus(), statusSummary(run.getStatus()), id(run.getRetryOfTaskRunId()),
                statusReason(run, requests, latestArtifact == null ? null : latestArtifact.getSummary()),
                artifactSummary(run.getId()), stepsFromArtifact(latestArtifact), iso(run.getStartedAt()), iso(run.getFinishedAt()),
                durationMs(run.getStartedAt(), run.getFinishedAt()), iso(run.getCreatedAt()),
                iso(run.getUpdatedAt()));
    }

    /**
     * 返回一个 TaskRun 的统一脱敏诊断。运行失败在调用 Worker 前发生时，仍会返回主后端失败
     * 原因和空 Worker 执行列表；空列表不是接口失败，也不代表调用者没有权限。
     */
    public TaskRunDiagnosticsResponse diagnostics(UUID projectId, UUID taskRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskRunEntity run = requireRun(projectId, taskRunId);
        requireTaskVisible(projectId, run.getTaskId(), userId);
        return diagnosticsForRun(run);
    }

    /** 供同一编排链路读取刚完成运行的脱敏失败字段，不对外暴露绕过项目权限的接口。 */
    public TaskRunEntity findById(UUID taskRunId) {
        return taskRunId == null ? null : taskRunMapper.selectById(taskRunId);
    }

    /**
     * 以 Task 为入口查询失败诊断。前端不需要先拿 executionId；服务端按项目和群成员权限
     * 找出最近失败的 TaskRun，并在 Planner/编排尚未创建 TaskRun 时返回 Task 级失败原因。
     * <p>
     * 仅任务处于失败终态（FAILED/DELIVERY_FAILED）时返回失败诊断：Test/Review 质量失败
     * 退回 Developer 修复期间任务仍为 RUNNING，历史上那条 FAILED 的 TaskRun 只是修复循环
     * 中的一次失败，不是任务失败，不应让前端展示「任务失败」横幅。
     */
    public TaskDiagnosticsResponse taskDiagnostics(UUID projectId, UUID taskId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskEntity task = requireTaskVisible(projectId, taskId, userId);
        boolean terminalFailure = "FAILED".equals(task.getStatus()) || "DELIVERY_FAILED".equals(task.getStatus());
        if (!terminalFailure) {
            return new TaskDiagnosticsResponse(id(task.getId()), task.getStatus(), taskFailureStage(task), null, null);
        }
        List<TaskRunEntity> failedRuns = taskRunMapper.selectList(Wrappers.<TaskRunEntity>lambdaQuery()
                .eq(TaskRunEntity::getProjectId, projectId)
                .eq(TaskRunEntity::getTaskId, taskId)
                .eq(TaskRunEntity::getStatus, "FAILED")
                .eq(task.getFailureCode() != null && !task.getFailureCode().isBlank()
                        && !task.getFailureCode().startsWith("TASK_FINALIZATION"),
                        TaskRunEntity::getFailureCode, task.getFailureCode())
                .orderByDesc(TaskRunEntity::getFinishedAt)
                .orderByDesc(TaskRunEntity::getCreatedAt)
                .last("LIMIT 1"));
        TaskRunDiagnosticsResponse latestFailedRun = failedRuns == null || failedRuns.isEmpty()
                ? null : diagnosticsForRun(failedRuns.get(0));
        TaskStatusReason failure = TaskStatusReasonFactory.taskFailure(task, latestFailedRun != null,
                latestFailedRun == null ? null : latestFailedRun.getFailure());
        String stage = latestFailedRun == null ? taskFailureStage(task) : latestFailedRun.getStage();
        return new TaskDiagnosticsResponse(id(task.getId()), task.getStatus(), stage, failure, latestFailedRun);
    }

    private String taskFailureStage(TaskEntity task) {
        if (task.getFailureCode() != null && (task.getFailureCode().startsWith("TASK_FINALIZATION")
                || task.getFailureCode().startsWith("FINAL_")
                || task.getFailureCode().contains("DIFF")
                || task.getFailureCode().contains("DELIVERY"))) {
            return "DELIVERY";
        }
        return "PLANNING";
    }

    private TaskRunDiagnosticsResponse diagnosticsForRun(TaskRunEntity run) {
        TaskExecutionArtifactEntity latestArtifact = latestRunArtifact(run.getId());
        Map<String, Object> artifactSummary = latestArtifact == null ? null : latestArtifact.getSummary();
        List<WorkerExecutionDiagnosticResponse> workerExecutions = workerExecutionMapper == null ? List.of()
                : Optional.ofNullable(workerExecutionMapper.selectList(
                        Wrappers.<TaskRunWorkerExecutionEntity>lambdaQuery()
                                .eq(TaskRunWorkerExecutionEntity::getProjectId, run.getProjectId())
                                .eq(TaskRunWorkerExecutionEntity::getTaskRunId, run.getId())
                                .eq(TaskRunWorkerExecutionEntity::getStatus, "FAILED")
                                .orderByDesc(TaskRunWorkerExecutionEntity::getCreatedAt)
                                .last("LIMIT 1")))
                .orElse(List.of()).stream()
                .filter(execution -> "FAILED".equals(execution.getStatus()))
                .limit(1)
                .map(this::toWorkerDiagnostic).toList();
        return new TaskRunDiagnosticsResponse(id(run.getId()), id(run.getTaskId()), run.getStatus(),
                diagnosticStage(run.getRole()), statusReason(run, List.of(), artifactSummary), workerExecutions);
    }

    /**
     * 重试 FAILED/CANCELLED/BLOCKED 的运行：受理后从该运行所属步骤续跑编排（202 受理）。
     * <p>
     * 先创建一条带 {@code retryOfTaskRunId} 的 QUEUED 运行，再发布续跑事件；编排器会复用该运行，
     * 这样接口立即返回真实的新运行 ID，客户端可以准确追踪重试进度，网络重复请求也不会产生第二条活动重试。
     */
    @Transactional
    public TaskRunSummaryResponse retry(UUID projectId, UUID taskRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskRunEntity source = requireRun(projectId, taskRunId);
        requireTaskVisible(projectId, source.getTaskId(), userId);
        requireOwner(source, projectId, userId);
        if (!RETRYABLE.contains(source.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_RUN_NOT_RETRYABLE", "仅 FAILED/CANCELLED/BLOCKED 状态可重试");
        }
        // 锁住 Task，串行化“检查可续跑 + 创建重试运行”，避免双击/网络重试产生两条活动运行。
        TaskEntity task = taskMapper.selectByIdForUpdate(source.getTaskId());
        if (task == null) {
            task = taskMapper.selectById(source.getTaskId());
        }
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或不可见");
        }
        if (source.getTaskStepId() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_RUN_NO_STEP", "该运行没有关联步骤，无法续跑");
        }
        // 进行中的任务由编排器内部重试/质量循环负责，不接受外部续跑；已交付/已完成的任务不接受重试。
        if (!RESUMABLE_TASK_STATUSES.contains(task.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_NOT_RESUMABLE",
                    "任务当前状态（" + task.getStatus() + "）不允许续跑");
        }
        // 确定性配置错误拒绝异步续跑：基线分支不存在等配置问题不会因重试自动恢复，异步受理后
        // 编排器必然再次失败，且失败被监听器静默吞掉，前端只会看到「重试已受理」却永远等不到
        // 新运行。这里同步返回 409，让前端立即展示可修复原因（如「修改基线分支后重试」）。
        String taskFailureCode = task.getFailureCode();
        if (isDeterministicConfigError(taskFailureCode)) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_RETRY_BLOCKED_BY_CONFIG",
                    "任务因配置问题失败（" + taskFailureCode + "），请先修复配置后重试："
                            + ExecutionContentSanitizer.userFailureDescription(taskFailureCode));
        }
        TaskRunEntity retryRun = taskRunMapper.selectActiveRetry(source.getTaskId(), source.getTaskStepId(), source.getId());
        if (retryRun == null) {
            retryRun = createForStep(projectId, source.getTaskId(), source.getTaskStepId(), source.getRole(),
                    source.getAgentId(), source.getCreatedBy(), source.getId());
        }
        // 异步续跑在事务提交后触发；编排侧认领 Task 并把上面的 QUEUED 运行切到 RUNNING。
        eventPublisher.publishEvent(new TaskResumeRequestedEvent(projectId, source.getTaskId(),
                source.getTaskStepId(), source.getId()));
        return toSummary(retryRun);
    }

    /**
     * 确定性配置错误：重试无法自动修复，必须先由用户修改仓库/分支/环境配置。
     * 这类失败码当前由 {@code ExecutionContentSanitizer.userFailureRetryable} 标为可重试，
     * 但那是对「基础设施瞬态」的语义；配置错误即使重试也必然再次失败，不应 202 受理后静默吞掉。
     */
    private static boolean isDeterministicConfigError(String failureCode) {
        if (failureCode == null || failureCode.isBlank()) {
            return false;
        }
        return switch (failureCode.toUpperCase(Locale.ROOT)) {
            case "GIT_BRANCH_NOT_FOUND", "GIT_BASE_REF_NOT_FOUND", "GIT_REF_NOT_FOUND",
                    "TEST_COMMAND_NOT_FOUND", "BUILD_ENVIRONMENT_UNAVAILABLE", "REVIEW_ASSERTION_TARGET_NOT_FOUND",
                    "QUALITY_REPAIR_STEP_UNAVAILABLE" -> true;
            default -> false;
        };
    }

    /**
     * 取消未完成运行：QUEUED 直接置 CANCELLED；RUNNING/WAITING_INPUT/WAITING_APPROVAL/BLOCKED 置
     * CANCELLING
     * （真实终止由执行器接缝完成）；SUCCEEDED/FAILED/CANCELLED/CANCELLING 不可取消。
     */
    @Transactional
    public TaskRunSummaryResponse cancel(UUID projectId, UUID taskRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskRunEntity run = taskRunMapper.selectByIdForUpdate(taskRunId);
        if (run == null) {
            run = requireRun(projectId, taskRunId);
        }
        requireTaskVisible(projectId, run.getTaskId(), userId);
        requireOwner(run, projectId, userId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if ("QUEUED".equals(run.getStatus())) {
            run.setStatus("CANCELLED");
            run.setFinishedAt(now);
            run.setUpdatedAt(now);
            taskRunMapper.updateById(run);
            taskRunLogService.append(run, "TERMINAL", run.getRole(), "运行已取消");
        } else if (CANCELLABLE_RUNNING.contains(run.getStatus())) {
            // 真实终止由执行器接缝在安全检查点完成，此处仅受理并标记
            run.setStatus("CANCELLING");
            run.setUpdatedAt(now);
            taskRunMapper.updateById(run);
            taskRunLogService.append(run, "SYSTEM", run.getRole(), "已请求取消，等待安全检查点");
        } else {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_RUN_NOT_CANCELLABLE", "当前状态不可取消");
        }
        eventService.publish(projectId, null, "task-run.updated", run.getId().toString(),
                eventPayload(run, 0));
        return toSummary(run);
    }

    /**
     * 获取工作流节点状态列表。
     */
    /**
     * 游标读取已脱敏的执行日志。
     *
     * @param cursor 上页最后一条日志的 sequence，首页为空
     */
    public ApiPageResponse<LogEntryResponse> logs(UUID projectId, UUID taskRunId, UUID userId, String cursor,
                                                  int limit, String requestId) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskRunEntity run = requireRun(projectId, taskRunId);
        requireTaskVisible(projectId, run.getTaskId(), userId);
        int size = clampLimit(limit);
        long after = parseLongCursor(cursor);
        List<ExecutionLogEntity> rows = logMapper.selectList(Wrappers.<ExecutionLogEntity>lambdaQuery()
                .eq(ExecutionLogEntity::getTaskRunId, taskRunId)
                .gt(ExecutionLogEntity::getSequenceNo, after)
                // 兼容隐藏历史版本曾写入的完整 Worker 输出；原记录保留供受控审计，
                // 但项目成员日志接口不得再返回 stdout/stderr。
                .and(query -> query.isNull(ExecutionLogEntity::getNode)
                        .or().notIn(ExecutionLogEntity::getNode, "WORKER/STDOUT", "WORKER/STDERR"))
                .orderByAsc(ExecutionLogEntity::getSequenceNo)
                .last("LIMIT " + (size + 1)));
        if (rows == null) {
            rows = List.of();
        }
        // 新运行的终态摘要由 TaskRunLogService 持久化；这里仅兼容历史运行或迁移前数据，
        // 只使用已落库的 TaskRun/执行产物摘要，不读取或暴露宿主机日志、Prompt、命令和凭据。
        if (rows.isEmpty() && after == 0) {
            LogEntryResponse terminal = terminalSummaryLog(run);
            if (terminal != null) {
                return new ApiPageResponse<>(List.of(terminal), new PageMeta(null, false), requestId);
            }
        }
        boolean hasMore = rows.size() > size;
        List<LogEntryResponse> items = (hasMore ? rows.subList(0, size) : rows).stream().map(this::toLog).toList();
        PageMeta page = new PageMeta(hasMore ? String.valueOf(items.get(items.size() - 1).getSequence()) : null,
                hasMore);
        return new ApiPageResponse<>(items, page, requestId);
    }

    /**
     * 读取 Workspace 与 Sandbox 的只读状态摘要。
     */
    public ExecutionContextResponse executionContext(UUID projectId, UUID taskRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskRunEntity run = requireRun(projectId, taskRunId);
        requireTaskVisible(projectId, run.getTaskId(), userId);
        TaskEntity task = taskMapper.selectById(run.getTaskId());
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或不可见");
        }
        // 仅返回只读摘要；宿主机路径、容器控制入口与凭据一律不返回。
        // 当前没有持久化 Sandbox 实体，sandboxStatus/expiresAt 必须返回 null，不能用 Workspace 状态冒充。
        // worktree 是当前唯一持久化的仓库/分支事实；无 worktree 的运行仍稳定返回 null 字段。
        List<WorkspaceRepositoryEntity> worktrees = task.getWorkspaceId() == null ? List.of()
                : workspaceRepositoryMapper.selectByWorkspace(task.getWorkspaceId());
        if (worktrees == null) {
            worktrees = List.of();
        }
        WorkspaceRepositoryEntity worktree = worktrees.stream().findFirst().orElse(null);
        String workspaceId = task.getWorkspaceId() == null ? null : id(task.getWorkspaceId());
        return new ExecutionContextResponse(workspaceId, null,
                worktree == null ? null : id(worktree.getProjectRepositoryId()),
                worktree == null ? null : worktree.getBaseRef(),
                worktree == null ? null : worktree.getSourceBranch(),
                iso(run.getStartedAt()), null);
    }

    /**
     * 查询运行期间发起的人机输入/审批请求。
     */
    public ApiPageResponse<InputRequestResponse> inputRequests(UUID projectId, UUID taskRunId, UUID userId,
                                                               String requestId) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskRunEntity run = requireRun(projectId, taskRunId);
        requireTaskVisible(projectId, run.getTaskId(), userId);
        List<InputRequestResponse> data = inputRequestMapper.selectList(Wrappers.<InputRequestEntity>lambdaQuery()
                        .eq(InputRequestEntity::getTaskRunId, taskRunId).orderByAsc(InputRequestEntity::getCreatedAt))
                .stream().map(this::toInput).toList();
        return new ApiPageResponse<>(data, new PageMeta(null, false), requestId);
    }

    /**
     * 回答 WAITING_INPUT 输入请求，回答后运行恢复 RUNNING。
     */
    @Transactional
    public InputRequestResponse replyInput(UUID projectId, UUID taskRunId, UUID requestId, UUID userId,
                                           Map<String, Object> answer) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskRunEntity run = requireRun(projectId, taskRunId);
        requireTaskVisible(projectId, run.getTaskId(), userId);
        requireOwner(run, projectId, userId);
        InputRequestEntity req = requireInput(run, requestId);
        if (!"PENDING".equals(req.getStatus()) || !"INPUT".equals(req.getKind())
                || !"WAITING_INPUT".equals(run.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "INPUT_REQUEST_NOT_ANSWERABLE", "该输入请求当前不可回答");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        req.setStatus("ANSWERED");
        req.setAnswer(answer);
        req.setResolvedAt(now);
        inputRequestMapper.updateById(req);
        run.setStatus("RUNNING");
        run.setUpdatedAt(now);
        taskRunMapper.updateById(run);
        taskRunLogService.append(run, "SYSTEM", run.getRole(), "收到输入，恢复执行");
        eventService.publish(projectId, null, "task-run.updated", run.getId().toString(),
                eventPayload(run, 0));
        return toInput(req);
    }

    /**
     * 批准 WAITING_APPROVAL 审批请求，批准后运行恢复 RUNNING（需 Project Admin）。
     */
    @Transactional
    public InputRequestResponse approveInput(UUID projectId, UUID taskRunId, UUID requestId, UUID userId,
                                             String reason) {
        projectAccess.requireProjectAdmin(projectId, userId);
        TaskRunEntity run = requireRun(projectId, taskRunId);
        requireTaskVisible(projectId, run.getTaskId(), userId);
        return decideInput(run, requestId, "APPROVED", reason);
    }

    /**
     * 拒绝 WAITING_APPROVAL 审批请求，拒绝后运行进入 BLOCKED（需 Project Admin）。
     */
    @Transactional
    public InputRequestResponse rejectInput(UUID projectId, UUID taskRunId, UUID requestId, UUID userId,
                                            String reason) {
        projectAccess.requireProjectAdmin(projectId, userId);
        TaskRunEntity run = requireRun(projectId, taskRunId);
        requireTaskVisible(projectId, run.getTaskId(), userId);
        return decideInput(run, requestId, "REJECTED", reason);
    }

    /**
     * 为指定 TaskStep 创建一次新的执行尝试（QUEUED）并发布事件。
     * 属于受控执行接缝：调用方必须已完成项目归属、角色与写入租约校验，本方法只负责落库与事件。
     *
     * @param retryOfTaskRunId 重试来源运行ID：基础设施重试指向同相位失败运行，质量修复循环指向触发修复的 Test/Review 运行，首次执行为 null
     */
    @Transactional
    public TaskRunEntity createForStep(UUID projectId, UUID taskId, UUID taskStepId, String role, UUID agentId,
                                       UUID createdBy, UUID retryOfTaskRunId) {
        if (retryOfTaskRunId != null) {
            TaskRunEntity existing = taskRunMapper.selectActiveRetry(taskId, taskStepId, retryOfTaskRunId);
            if (existing != null) {
                return existing;
            }
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TaskRunEntity run = new TaskRunEntity();
        run.setId(UuidV7.next());
        run.setProjectId(projectId);
        run.setTaskId(taskId);
        run.setTaskStepId(taskStepId);
        run.setAgentId(agentId);
        run.setRole(role);
        run.setStatus("QUEUED");
        run.setRetryOfTaskRunId(retryOfTaskRunId);
        run.setCreatedBy(createdBy);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        taskRunMapper.insert(run);
        taskRunLogService.append(run, "SYSTEM", run.getRole(), "运行已排队");
        eventService.publish(projectId, null, "task-run.updated", run.getId().toString(), eventPayload(run, 0));
        return run;
    }

    /**
     * 受控执行接缝：为运行创建人机输入/审批请求，并将运行置为 WAITING_INPUT/WAITING_APPROVAL。
     * 发布 input-required / approval-required 与 task-run.updated 事件；调用方必须已完成
     * 项目归属、角色与写入租约校验。回复/审批入口见 replyInput/approveInput/rejectInput。
     */
    @Transactional
    public InputRequestResponse createInputRequest(UUID projectId, UUID taskId, UUID taskStepId, UUID taskRunId,
                                                   String kind, String prompt, List<Object> options, UUID createdBy) {
        TaskRunEntity run = requireRun(projectId, taskRunId);
        if (!"INPUT".equals(kind) && !"APPROVAL".equals(kind)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_INPUT_KIND", "非法输入请求类型");
        }
        if (!"RUNNING".equals(run.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_RUN_NOT_WAITABLE", "仅 RUNNING 运行可发起输入请求");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        InputRequestEntity req = new InputRequestEntity();
        req.setId(UuidV7.next());
        req.setTaskRunId(taskRunId);
        req.setKind(kind);
        req.setStatus("PENDING");
        req.setPrompt(prompt);
        req.setOptions(options);
        req.setCreatedBy(createdBy);
        req.setCreatedAt(now);
        inputRequestMapper.insert(req);
        run.setStatus("INPUT".equals(kind) ? "WAITING_INPUT" : "WAITING_APPROVAL");
        run.setUpdatedAt(now);
        taskRunMapper.updateById(run);
        taskRunLogService.append(run, "SYSTEM", run.getRole(),
                "等待" + ("INPUT".equals(kind) ? "用户输入" : "审批"));
        String eventType = "INPUT".equals(kind) ? "input-required" : "approval-required";
        eventService.publish(projectId, null, eventType, req.getId().toString(),
                TaskEventPayloads.inputRequest(projectId, taskId, taskStepId, taskRunId, req));
        eventService.publish(projectId, null, "task-run.updated", run.getId().toString(), eventPayload(run, 0));
        // 通知任务发起人需要输入/审批（接收人 = 运行发起用户，语义与任务发起人一致）
        notificationService.notify(run.getCreatedBy(), projectId, null, "AGENT_INPUT_REQUIRED",
                "INPUT".equals(kind) ? "需要你输入：" + prompt : "需要你审批：" + prompt, prompt,
                req.getId().toString());
        return toInput(req);
    }

    /**
     * QUEUED → RUNNING，记录开始时间；仅 QUEUED 状态可开始。
     */
    @Transactional
    public void markRunning(UUID taskRunId) {
        // 完成与取消必须在同一行锁上串行化，避免迟到的 Agent 结果覆盖用户取消。
        TaskRunEntity run = taskRunMapper.selectByIdForUpdate(taskRunId);
        if (run == null) {
            run = taskRunMapper.selectById(taskRunId);
        }
        if (run == null || !"QUEUED".equals(run.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_RUN_NOT_STARTABLE", "仅 QUEUED 运行可开始");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        run.setStatus("RUNNING");
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        taskRunMapper.updateById(run);
        taskRunLogService.append(run, "SYSTEM", run.getRole(), "开始执行");
        eventService.publish(run.getProjectId(), null, "task-run.updated", run.getId().toString(), eventPayload(run, 0));
    }

    /**
     * RUNNING → 终态（SUCCEEDED/FAILED/CANCELLED），记录结束时间并发布事件。
     * 属于受控执行接缝：状态由确定性 Orchestrator 依据 Agent 结果映射，本方法不自行判断。
     */
    @Transactional
    public void complete(UUID taskRunId, String terminalStatus) {
        complete(taskRunId, terminalStatus, null, null);
    }

    /** 完成运行并把稳定失败码对应的受控说明写入终态日志。 */
    @Transactional
    public void complete(UUID taskRunId, String terminalStatus, String failureCode, String detail) {
        if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(terminalStatus)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RUN_TERMINAL_STATUS", "非法运行终态");
        }
        TaskRunEntity run = taskRunMapper.selectById(taskRunId);
        if (run == null || (!"RUNNING".equals(run.getStatus()) && !"CANCELLING".equals(run.getStatus()))) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_RUN_NOT_COMPLETABLE", "仅 RUNNING/CANCELLING 运行可完成");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 取消是用户已经确认的终态意图。执行器可能在读取状态后才收到取消请求，
        // 因此不能让迟到的成功/失败结果覆盖 RUNNING -> CANCELLING。
        if ("CANCELLING".equals(run.getStatus())) {
            terminalStatus = "CANCELLED";
        }
        // CANCELLING → CANCELLED 收敛：取消受理后编排器在安全点把运行终态化为 CANCELLED，
        // 不再要求必须是 RUNNING（否则取消请求发出后 run 永远无法落终态）。
        run.setStatus(terminalStatus);
        run.setFinishedAt(now);
        run.setUpdatedAt(now);
        if ("FAILED".equals(terminalStatus)) {
            String safeFailureCode = safeFailureCode(failureCode);
            run.setFailureCode(safeFailureCode);
            run.setFailureReason(safeFailureReason(safeFailureCode));
            run.setFailureOccurredAt(now);
        } else {
            run.setFailureCode(null);
            run.setFailureReason(null);
            run.setFailureOccurredAt(null);
        }
        taskRunMapper.updateById(run);
        String message = switch (terminalStatus) {
            case "SUCCEEDED" -> "执行成功";
            case "CANCELLED" -> "执行已取消";
            default -> "执行失败";
        };
        if ("FAILED".equals(terminalStatus)) {
            String publicFailureCode = ExecutionContentSanitizer.publicFailureCode(failureCode);
            message = publicFailureCode == null ? "执行失败：任务执行失败，请查看执行记录"
                    : "执行失败（" + publicFailureCode + "）："
                    + ExecutionContentSanitizer.userFailureDescription(publicFailureCode);
        }
        taskRunLogService.append(run, "TERMINAL", run.getRole(), message);
        eventService.publish(run.getProjectId(), null, "task-run.updated", run.getId().toString(), eventPayload(run, 0));
    }

    /**
     * 批准/拒绝 WAITING_APPROVAL 请求：批准恢复 RUNNING，拒绝进入 BLOCKED。
     */
    private InputRequestResponse decideInput(TaskRunEntity run, UUID requestId, String decision, String reason) {
        InputRequestEntity req = requireInput(run, requestId);
        if (!"PENDING".equals(req.getStatus()) || !"APPROVAL".equals(req.getKind())
                || !"WAITING_APPROVAL".equals(run.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "INPUT_REQUEST_NOT_DECIDABLE", "该审批请求当前不可处理");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        req.setStatus(decision);
        req.setReason(reason);
        req.setResolvedAt(now);
        inputRequestMapper.updateById(req);
        run.setStatus("APPROVED".equals(decision) ? "RUNNING" : "BLOCKED");
        run.setUpdatedAt(now);
        taskRunMapper.updateById(run);
        taskRunLogService.append(run, "SYSTEM", run.getRole(),
                "APPROVED".equals(decision) ? "审批通过，恢复执行" : "审批拒绝，运行阻塞");
        eventService.publish(run.getProjectId(), null, "task-run.updated",
                run.getId().toString(), eventPayload(run, 0));
        return toInput(req);
    }

    // ---------- 私有辅助 ----------

    /**
     * TaskRun 的可见性继承所属 Task 的需求群边界，避免项目成员仅凭运行 UUID
     * 读取其他需求群的日志、执行环境或输入请求。
     */
    private TaskEntity requireTaskVisible(UUID projectId, UUID taskId, UUID userId) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在或不可见");
        }
        if (task.getRequirementGroupId() != null) {
            groupService.requireGroupMember(projectId, task.getRequirementGroupId(), userId);
        }
        return task;
    }

    /**
     * 加载运行并校验其归属路径项目，防止跨项目仅凭 UUID 查询。
     */
    private TaskRunEntity requireRun(UUID projectId, UUID taskRunId) {
        TaskRunEntity run = taskRunMapper.selectById(taskRunId);
        if (run == null || !run.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_RUN_NOT_FOUND", "任务运行不存在或不可见");
        }
        return run;
    }

    /**
     * 发起人或 Project Admin 才允许操作，否则 403。
     */
    private void requireOwner(TaskRunEntity run, UUID projectId, UUID userId) {
        if (!projectAccess.isOwnerOrAdmin(run.getCreatedBy(), projectId, userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TASK_RUN_FORBIDDEN", "仅发起人或 Project Admin 可操作该运行");
        }
    }

    /**
     * 加载输入请求并校验其归属于该运行。
     */
    private InputRequestEntity requireInput(TaskRunEntity run, UUID requestId) {
        InputRequestEntity req = inputRequestMapper.selectById(requestId);
        if (req == null || !run.getId().equals(req.getTaskRunId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "INPUT_REQUEST_NOT_FOUND", "输入请求不存在或不可见");
        }
        return req;
    }

    /**
     * 该运行自身产出的产物与 Diff 数量摘要（total=执行产物数，diffCount=总 Diff 数）。
     */
    private Map<String, Object> artifactSummary(UUID taskRunId) {
        long total = artifactMapper.selectCount(Wrappers.<TaskExecutionArtifactEntity>lambdaQuery()
                .eq(TaskExecutionArtifactEntity::getTaskRunId, taskRunId));
        long diffCount = diffMapper.selectCount(Wrappers.<DiffEntity>lambdaQuery()
                .eq(DiffEntity::getTaskRunId, taskRunId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("diffCount", diffCount);
        return result;
    }

    /**
     * 没有执行器日志时，为迁移前的终态运行生成一条脱敏结果摘要，避免历史数据无法解释。
     * 新运行的摘要已写入 execution_logs；该兼容兜底不表示存在内部节点轨迹。
     */
    private LogEntryResponse terminalSummaryLog(TaskRunEntity run) {
        if (run == null || !("SUCCEEDED".equals(run.getStatus()) || "FAILED".equals(run.getStatus())
                || "BLOCKED".equals(run.getStatus()) || "CANCELLED".equals(run.getStatus()))) {
            return null;
        }
        TaskExecutionArtifactEntity artifact = latestRunArtifact(run.getId());
        TaskStatusReason reason = statusReason(run, List.of(), artifact == null ? null : artifact.getSummary());
        if (reason == null && "SUCCEEDED".equals(run.getStatus())) {
            reason = new TaskStatusReason("SUCCEEDED", "执行成功", "运行已完成", false,
                    iso(run.getFinishedAt() == null ? run.getUpdatedAt() : run.getFinishedAt()));
        }
        if (reason == null) {
            return null;
        }
        String content = reason.getTitle() + "：" + reason.getSummary();
        String timestamp = iso(run.getFinishedAt() == null ? run.getUpdatedAt() : run.getFinishedAt());
        return new LogEntryResponse("terminal-" + id(run.getId()), 1L, "TERMINAL",
                run.getRole() == null || run.getRole().isBlank() ? "SYSTEM" : run.getRole(), content, timestamp);
    }

    /**
     * 将执行器写入 Run 产物的脱敏 LLM 观测转换为详情中的内部节点轨迹。
     *
     * <p>这里不重新引入 TaskRunStep 持久化模型：TaskRun 产物就是执行器的事实来源。
     * 仅投影 phase/round、服务端时序、状态和错误码；promptChars、responseChars、工具响应摘要和
     * responseSha256 等内部度量不会进入 TaskRunStepResponse。</p>
     */
    private List<TaskRunStepResponse> stepsFromArtifact(TaskExecutionArtifactEntity artifact) {
        if (artifact == null || artifact.getSummary() == null) {
            return List.of();
        }
        Object value = artifact.getSummary().get("observations");
        if (!(value instanceof Collection<?> observations) || observations.isEmpty()) {
            return List.of();
        }

        List<TaskRunStepResponse> result = new ArrayList<>();
        for (Object observation : observations) {
            if (!(observation instanceof Map<?, ?> fields)) {
                continue;
            }
            String phase = safeText(fields.get("phase"));
            Integer round = positiveInteger(fields.get("round"));
            if (phase == null || round == null) {
                continue;
            }
            String protocolFailureCode = safeText(fields.get("protocolFailureCode"));
            String errorCode = safeText(fields.get("errorCode"));
            if (errorCode == null) {
                errorCode = protocolFailureCode;
            }
            String status = safeText(fields.get("status"));
            if (status == null) {
                status = protocolFailureCode == null ? "PASSED" : "FAILED";
            }
            result.add(new TaskRunStepResponse(
                    phase + "#round-" + round, status,
                    safeText(fields.get("startedAt")), safeText(fields.get("finishedAt")),
                    positiveLong(fields.get("durationMs")), errorCode));
        }
        return result;
    }

    private String safeText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isBlank() ? null : text;
    }

    private Integer positiveInteger(Object value) {
        if (value instanceof Number number) {
            int result = number.intValue();
            return result > 0 ? result : null;
        }
        if (value instanceof String text) {
            try {
                int result = Integer.parseInt(text.strip());
                return result > 0 ? result : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Long positiveLong(Object value) {
        if (value instanceof Number number) {
            long result = number.longValue();
            return result >= 0 ? result : null;
        }
        if (value instanceof String text) {
            try {
                long result = Long.parseLong(text.strip());
                return result >= 0 ? result : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 项目级按 Agent 查询 TaskRun（契约 v1.8.0 §20，成员 B B05）；agentId 必填。
     */
    public ApiPageResponse<TaskRunListItemResponse> listByAgent(UUID projectId, UUID agentId, UUID userId,
                                                                String status, String cursor, int limit,
                                                                String requestId) {
        projectAccess.requireProjectMember(projectId, userId);
        Set<UUID> visibleGroupIds = new HashSet<>(groupService.visibleGroupIds(projectId, userId));
        Set<UUID> visibleTaskIds = taskMapper.selectList(Wrappers.<TaskEntity>lambdaQuery()
                        .eq(TaskEntity::getProjectId, projectId)).stream()
                .filter(task -> task.getRequirementGroupId() == null
                        || visibleGroupIds.contains(task.getRequirementGroupId()))
                .map(TaskEntity::getId).collect(Collectors.toSet());
        if (visibleTaskIds.isEmpty()) {
            return new ApiPageResponse<>(List.of(), new PageMeta(null, false), requestId);
        }
        int size = clampLimit(limit);
        UUID cursorUuid = parseCursor(cursor);
        List<TaskRunEntity> rows = taskRunMapper.selectList(Wrappers.<TaskRunEntity>lambdaQuery()
                .eq(TaskRunEntity::getProjectId, projectId)
                .eq(TaskRunEntity::getAgentId, agentId)
                .in(TaskRunEntity::getTaskId, visibleTaskIds)
                .eq(status != null && !status.isBlank(), TaskRunEntity::getStatus, status)
                .lt(cursorUuid != null, TaskRunEntity::getId, cursorUuid).orderByDesc(TaskRunEntity::getId)
                .last("LIMIT " + (size + 1)));
        boolean hasMore = rows.size() > size;
        List<TaskRunEntity> page = hasMore ? rows.subList(0, size) : rows;
        List<TaskRunListItemResponse> items = page.isEmpty() ? List.of() : buildListItems(page);
        String next = hasMore ? items.get(items.size() - 1).getId() : null;
        return new ApiPageResponse<>(items, new PageMeta(next, hasMore), requestId);
    }

    /**
     * 批量构造任务运行列表项；步骤、Agent、输入请求、产物与 Diff 一次性加载，避免逐条运行 N+1。
     * 同时批量加载 Task/需求群/仓库摘要，供 Agent 页与任务详情页展示。
     */
    private List<TaskRunListItemResponse> buildListItems(List<TaskRunEntity> page) {
        List<UUID> runIds = page.stream().map(TaskRunEntity::getId).toList();
        Set<UUID> stepIds = page.stream().map(TaskRunEntity::getTaskStepId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> agentIds = page.stream().map(TaskRunEntity::getAgentId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> taskIds = page.stream().map(TaskRunEntity::getTaskId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 空 Map 用 emptyMap 保证 null 键查找返回 null（TaskRun.agentId 可为 null，Map.of() 的 get(null) 会抛 NPE）
        Map<UUID, TaskStepEntity> stepById = stepIds.isEmpty() ? Collections.emptyMap()
                : taskStepMapper.selectList(Wrappers.<TaskStepEntity>lambdaQuery().in(TaskStepEntity::getId, stepIds))
                .stream().collect(Collectors.toMap(TaskStepEntity::getId, Function.identity()));
        Map<UUID, AgentEntity> agentById = agentIds.isEmpty() ? Collections.emptyMap()
                : agentMapper.selectList(Wrappers.<AgentEntity>lambdaQuery().in(AgentEntity::getId, agentIds)).stream()
                .collect(Collectors.toMap(AgentEntity::getId, Function.identity()));
        Map<UUID, TaskEntity> taskById = taskIds.isEmpty() ? Collections.emptyMap()
                : taskMapper.selectList(Wrappers.<TaskEntity>lambdaQuery().in(TaskEntity::getId, taskIds)).stream()
                .collect(Collectors.toMap(TaskEntity::getId, Function.identity()));
        Set<UUID> groupIds = taskById.values().stream().map(TaskEntity::getRequirementGroupId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, RequirementGroupEntity> groupById = groupIds.isEmpty() ? Collections.emptyMap()
                : requirementGroupMapper
                .selectList(Wrappers.<RequirementGroupEntity>lambdaQuery().in(RequirementGroupEntity::getId, groupIds))
                .stream().collect(Collectors.toMap(RequirementGroupEntity::getId, Function.identity()));
        Set<UUID> workspaceIds = taskById.values().stream().map(TaskEntity::getWorkspaceId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, WorkspaceRepositoryEntity> firstWorktreeByWorkspace = workspaceIds.isEmpty()
                ? Collections.emptyMap()
                : workspaceRepositoryMapper.selectByWorkspaces(new ArrayList<>(workspaceIds)).stream()
                .collect(Collectors.toMap(WorkspaceRepositoryEntity::getWorkspaceId,
                        Function.identity(), (a, b) -> a));
        Set<UUID> bindingIds = firstWorktreeByWorkspace.values().stream()
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId).collect(Collectors.toSet());
        Map<UUID, ProjectRepositoryEntity> bindingById = bindingIds.isEmpty() ? Collections.emptyMap()
                : projectRepositoryMapper
                .selectList(Wrappers.<ProjectRepositoryEntity>lambdaQuery().in(ProjectRepositoryEntity::getId, bindingIds))
                .stream().collect(Collectors.toMap(ProjectRepositoryEntity::getId, Function.identity()));
        Map<UUID, List<InputRequestEntity>> inputByRun = runIds.isEmpty() ? Collections.emptyMap()
                : inputRequestMapper
                .selectList(Wrappers.<InputRequestEntity>lambdaQuery()
                        .in(InputRequestEntity::getTaskRunId, runIds))
                .stream().collect(Collectors.groupingBy(InputRequestEntity::getTaskRunId));
        List<TaskExecutionArtifactEntity> artifactRows = runIds.isEmpty() ? List.of()
                : Optional.ofNullable(artifactMapper.selectList(Wrappers.<TaskExecutionArtifactEntity>lambdaQuery()
                        .in(TaskExecutionArtifactEntity::getTaskRunId, runIds)
                        .orderByDesc(TaskExecutionArtifactEntity::getSequenceNo))).orElse(List.of());
        Map<UUID, Map<String, Object>> failureSummaryByRun = artifactRows.stream()
                .filter(artifact -> artifact.getTaskRunId() != null)
                .filter(artifact -> artifact.getSummary() != null)
                .collect(Collectors.toMap(TaskExecutionArtifactEntity::getTaskRunId,
                        TaskExecutionArtifactEntity::getSummary, (first, ignored) -> first));
        Map<UUID, Long> artifactCountByRun = runIds.isEmpty() ? Collections.emptyMap()
                : artifactMapper
                .selectList(Wrappers.<TaskExecutionArtifactEntity>lambdaQuery()
                        .in(TaskExecutionArtifactEntity::getTaskRunId, runIds))
                .stream().collect(Collectors.groupingBy(TaskExecutionArtifactEntity::getTaskRunId,
                        Collectors.counting()));
        Map<UUID, Long> diffCountByRun = runIds.isEmpty() ? Collections.emptyMap()
                : diffMapper.selectList(Wrappers.<DiffEntity>lambdaQuery().in(DiffEntity::getTaskRunId, runIds))
                .stream().collect(Collectors.groupingBy(DiffEntity::getTaskRunId, Collectors.counting()));
        return page.stream().map(run -> toListItem(run, stepById.get(run.getTaskStepId()),
                        agentById.get(run.getAgentId()), taskById.get(run.getTaskId()),
                        groupById.get(taskById.get(run.getTaskId()) == null ? null
                                : taskById.get(run.getTaskId()).getRequirementGroupId()),
                        repositorySummary(taskById.get(run.getTaskId()), firstWorktreeByWorkspace, bindingById),
                        inputByRun.getOrDefault(run.getId(), List.of()),
                        failureSummaryByRun.get(run.getId()),
                        artifactCountByRun.getOrDefault(run.getId(), 0L), diffCountByRun.getOrDefault(run.getId(), 0L)))
                .toList();
    }

    private TaskRunListItemResponse toListItem(TaskRunEntity run, TaskStepEntity step, AgentEntity agent,
                                               TaskEntity task, RequirementGroupEntity group,
                                               RepositorySummary repository, List<InputRequestEntity> requests,
                                               Map<String, Object> failureSummary,
                                               long artifactTotal, long diffCount) {
        Map<String, Object> artifactSummary = new LinkedHashMap<>();
        artifactSummary.put("total", artifactTotal);
        artifactSummary.put("diffCount", diffCount);
        RequirementGroupSummary groupSummary = group == null ? null
                : new RequirementGroupSummary(id(group.getId()), group.getName(), group.getStatus());
        return new TaskRunListItemResponse(id(run.getId()), id(run.getTaskId()), id(run.getTaskStepId()),
                task == null ? null : task.getDisplayCode(), task == null ? null : task.getTitle(),
                step == null ? null : step.getTitle(), step == null ? null : step.getRole(),
                groupSummary, repository, run.getRole(), agentSummary(agent), run.getStatus(),
                statusSummary(run.getStatus()), statusReason(run, requests, failureSummary), id(run.getRetryOfTaskRunId()),
                iso(run.getStartedAt()), iso(run.getFinishedAt()),
                durationMs(run.getStartedAt(), run.getFinishedAt()), artifactSummary, iso(run.getCreatedAt()),
                iso(run.getUpdatedAt()));
    }

    /**
     * 取任务第一个 worktree 对应仓库的轻量摘要（repositoryId + 展示名，其余字段为空）。
     */
    private RepositorySummary repositorySummary(TaskEntity task,
                                                Map<UUID, WorkspaceRepositoryEntity> firstWorktreeByWorkspace,
                                                Map<UUID, ProjectRepositoryEntity> bindingById) {
        if (task == null || task.getWorkspaceId() == null) {
            return null;
        }
        WorkspaceRepositoryEntity worktree = firstWorktreeByWorkspace.get(task.getWorkspaceId());
        if (worktree == null) {
            return null;
        }
        ProjectRepositoryEntity binding = bindingById.get(worktree.getProjectRepositoryId());
        return new RepositorySummary(id(worktree.getProjectRepositoryId()),
                binding == null ? null : binding.getDisplayName(), null, null,
                binding == null ? null : binding.getDefaultBranch(), worktree.getBaseRef(),
                worktree.getBaseCommit(), worktree.getSourceBranch(), worktree.getHeadCommit());
    }

    /**
     * 等待/阻塞/失败原因摘要，仅返回脱敏用户可见文本；无等待或失败时返回 null。
     */
    private TaskStatusReason statusReason(TaskRunEntity run, List<InputRequestEntity> requests,
                                          Map<String, Object> failureSummary) {
        return switch (run.getStatus()) {
            case "WAITING_INPUT" -> {
                InputRequestEntity req = latestPending(requests, "INPUT");
                yield new TaskStatusReason("INPUT_REQUIRED", "等待用户输入",
                        req == null ? "等待用户补充输入" : req.getPrompt(), false,
                        iso(req == null ? run.getUpdatedAt() : req.getCreatedAt()));
            }
            case "WAITING_APPROVAL" -> {
                InputRequestEntity req = latestPending(requests, "APPROVAL");
                yield new TaskStatusReason("APPROVAL_REQUIRED", "等待审批",
                        req == null ? "等待审批确认" : req.getPrompt(), false,
                        iso(req == null ? run.getUpdatedAt() : req.getCreatedAt()));
            }
            case "BLOCKED" -> {
                InputRequestEntity req = latestRequest(requests, "APPROVAL");
                String reason = req == null ? null : req.getReason();
                yield new TaskStatusReason("BLOCKED", "执行被阻塞",
                        reason == null || reason.isBlank() ? "执行流程被阻塞，等待处理" : reason, true,
                        iso(run.getUpdatedAt()));
            }
            case "FAILED" -> failedReason(run, failureSummary);
            case "CANCELLING", "CANCELLED" -> new TaskStatusReason("CANCELLED", "已取消", "任务运行已取消", false,
                    iso(run.getUpdatedAt()));
            default -> null;
        };
    }

    private TaskStatusReason failedReason(TaskRunEntity run, Map<String, Object> failureSummary) {
        // failureReason 属于持久化的内部诊断字段，历史数据可能写入过上游 HTTP/模型原文。
        // 对外永远只由公开稳定码派生受控文案，详情只保留在受限诊断表中。
        String persistedCode = run.getFailureCode();
        if (persistedCode != null && !persistedCode.isBlank()) {
            String failureCode = ExecutionContentSanitizer.publicFailureCode(persistedCode);
            return new TaskStatusReason("EXECUTION_FAILED", failureCode, "执行失败",
                    ExecutionContentSanitizer.userFailureDescription(failureCode),
                    ExecutionContentSanitizer.userFailureRetryable(failureCode),
                    iso(run.getFailureOccurredAt() == null ? run.getUpdatedAt() : run.getFailureOccurredAt()));
        }
        String failureCode = ExecutionContentSanitizer.publicFailureCode(text(failureSummary, "failureCode"));
        String message = ExecutionContentSanitizer.userFailureDescription(failureCode);
        return new TaskStatusReason("EXECUTION_FAILED", failureCode, "执行失败", message,
                ExecutionContentSanitizer.userFailureRetryable(failureCode), iso(run.getUpdatedAt()));
    }

    private WorkerExecutionDiagnosticResponse toWorkerDiagnostic(TaskRunWorkerExecutionEntity execution) {
        String failureCode = ExecutionContentSanitizer.publicFailureCode(execution.getFailureCode());
        return new WorkerExecutionDiagnosticResponse(id(execution.getExecutionId()), execution.getToolName(),
                execution.getStatus(), execution.getExitCode(), failureCode,
                failureCode == null ? null : ExecutionContentSanitizer.userFailureDescription(failureCode),
                iso(execution.getCreatedAt()), iso(execution.getFinishedAt()));
    }

    private String diagnosticStage(String role) {
        return switch (role == null ? "" : role) {
            case "PLANNER" -> "PLANNING";
            case "DEVELOPER" -> "CODING";
            case "TESTER" -> "TESTING";
            case "REVIEWER" -> "REVIEWING";
            default -> role == null || role.isBlank() ? "UNKNOWN" : role;
        };
    }

    private String safeFailureCode(String code) {
        String publicCode = ExecutionContentSanitizer.publicFailureCode(code);
        if (publicCode != null) {
            return publicCode;
        }
        // 未定义内部码不进入项目成员可读的 TaskRun 字段；原码由失败诊断表受限保存。
        return "FAILED_INFRASTRUCTURE";
    }

    private String safeFailureReason(String publicFailureCode) {
        // 禁止持久化 Agent/供应商异常原文到可由项目成员读取的 TaskRun；
        // 原始失败上下文已经由 TaskRunFailureDiagnosticService 严格脱敏后受限落库。
        return ExecutionContentSanitizer.userFailureDescription(publicFailureCode);
    }

    private String text(Map<String, Object> values, String key) {
        if (values == null || values.get(key) == null) {
            return null;
        }
        String value = String.valueOf(values.get(key)).strip();
        return value.isBlank() ? null : value;
    }

    private TaskExecutionArtifactEntity latestRunArtifact(UUID taskRunId) {
        List<TaskExecutionArtifactEntity> rows = artifactMapper.selectList(Wrappers.<TaskExecutionArtifactEntity>lambdaQuery()
                        .eq(TaskExecutionArtifactEntity::getTaskRunId, taskRunId)
                        .orderByDesc(TaskExecutionArtifactEntity::getSequenceNo)
                        .last("LIMIT 1"));
        return Optional.ofNullable(rows).orElse(List.of())
                .stream().findFirst().orElse(null);
    }

    /**
     * 脱敏状态摘要：不返回日志原文、Prompt、Token 或环境变量。
     */
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

    private InputRequestEntity latestPending(List<InputRequestEntity> requests, String kind) {
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

    private AgentSummary agentSummary(AgentEntity agent) {
        if (agent == null) {
            return null;
        }
        return new AgentSummary(id(agent.getId()), agent.getName(), agent.getRole(), agent.getAvatar(),
                agent.getStatus());
    }

    /**
     * 计算执行耗时（毫秒）；任一端时间为空或结束早于开始时返回 null，避免负值误导前端。
     */
    private Long durationMs(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (startedAt == null || finishedAt == null || finishedAt.isBefore(startedAt)) {
            return null;
        }
        return java.time.Duration.between(startedAt, finishedAt).toMillis();
    }

    /**
     * 构造 task-run.updated 事件载荷；sequence 为运行内执行序号，状态事件暂无步骤序号填 0。
     */
    private Map<String, Object> eventPayload(TaskRunEntity run, long sequence) {
        return TaskEventPayloads.taskRunUpdated(run, sequence);
    }

    private TaskRunSummaryResponse toSummary(TaskRunEntity run) {
        return new TaskRunSummaryResponse(id(run.getId()), id(run.getProjectId()), id(run.getTaskId()),
                id(run.getTaskStepId()), id(run.getAgentId()),
                run.getRole(), run.getStatus(), id(run.getRetryOfTaskRunId()),
                iso(run.getCreatedAt()), iso(run.getUpdatedAt()));
    }

    private LogEntryResponse toLog(ExecutionLogEntity l) {
        return new LogEntryResponse(id(l.getId()), l.getSequenceNo(),
                l.getEntryType() == null ? "EXECUTION" : l.getEntryType(), l.getNode(), l.getContent(),
                iso(l.getCreatedAt()));
    }

    /**
     * 将 Worker 工具执行摘要写入 TaskRun 公共日志。只输出稳定失败码和受控说明；
     * 原始 failureReason 仅保留在 Worker 内网诊断记录，不能进入项目成员可见的 REST 或 SSE 日志。
     */
    public void appendWorkerToolExecution(TaskRunEntity run, WorkerToolExecution execution) {
        if (execution == null || execution.getId() == null || !"FAILED".equals(execution.getStatus())) {
            return;
        }
        StringBuilder content = new StringBuilder("executionId=").append(execution.getId())
                .append("，tool=").append(execution.getTool())
                .append("，status=").append(execution.getStatus());
        if (execution.getExitCode() != null) {
            content.append("，exitCode=").append(execution.getExitCode());
        }
        String failureCode = ExecutionContentSanitizer.publicFailureCode(execution.getFailureCode());
        if (failureCode != null) {
            content.append("，failureCode=").append(failureCode)
                    .append("，failureReason=")
                    .append(ExecutionContentSanitizer.userFailureDescription(failureCode));
        }
        taskRunLogService.append(run, "EXECUTION", "WORKER/" + (execution.getTool() == null
                ? "TOOL" : execution.getTool()), content.toString());
    }

    /** 将 TestAgent 收集的 Worker stdout/stderr 接入统一日志入口。 */
    public void appendWorkerOutput(TaskRunEntity run, String stream, String output) {
        taskRunLogService.appendWorkerOutput(run, stream, output);
    }

    /** 将 Verify 的命令、退出码、摘要和失败项投影到统一运行日志。 */
    public void appendVerificationResult(TaskRunEntity run, TestResult result) {
        taskRunLogService.appendVerificationResult(run, result);
    }

    /** 将 Agent 每轮脱敏观测投影为可游标读取的执行日志；完整原文仍只留在结构化摘要中。 */
    public void appendAgentObservations(TaskRunEntity run, List<LlmObservation> observations) {
        if (observations == null) {
            return;
        }
        for (LlmObservation observation : observations) {
            if (observation == null) {
                continue;
            }
            StringBuilder content = new StringBuilder("Agent 轮次完成");
            if (observation.status() != null) {
                content.append("，状态=").append(observation.status());
            }
            if (observation.finishReason() != null) {
                content.append("，结束原因=").append(observation.finishReason());
            }
            if (observation.durationMs() != null) {
                content.append("，耗时=").append(observation.durationMs()).append("ms");
            }
            if (observation.errorCode() != null) {
                content.append("，错误码=").append(observation.errorCode());
            }
            taskRunLogService.append(run, "EXECUTION",
                    observation.phase() + "#round-" + observation.round(), content.toString());
        }
    }

    private InputRequestResponse toInput(InputRequestEntity r) {
        return new InputRequestResponse(id(r.getId()), id(r.getTaskRunId()), r.getKind(), r.getStatus(), r.getPrompt(),
                r.getOptions(), r.getAnswer(), r.getReason(), iso(r.getCreatedAt()), iso(r.getResolvedAt()));
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

    private long parseLongCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            long v = Long.parseLong(cursor);
            if (v < 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "游标格式不合法");
        }
    }

    private int clampLimit(int limit) {
        if (limit <= 0) {
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
}
