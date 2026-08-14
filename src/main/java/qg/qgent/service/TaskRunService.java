package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.AgentSummary;
import qg.qgent.dto.ApiPageResponse;
import qg.qgent.dto.ExecutionContextResponse;
import qg.qgent.dto.InputRequestResponse;
import qg.qgent.dto.LogEntryResponse;
import qg.qgent.dto.PageMeta;
import qg.qgent.dto.TaskRunDetailResponse;
import qg.qgent.dto.TaskRunListItemResponse;
import qg.qgent.dto.TaskRunSummaryResponse;
import qg.qgent.dto.TaskStatusReason;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.DiffEntity;
import qg.qgent.entity.ExecutionLogEntity;
import qg.qgent.entity.InputRequestEntity;
import qg.qgent.entity.TaskExecutionArtifactEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.DiffMapper;
import qg.qgent.mapper.ExecutionLogMapper;
import qg.qgent.mapper.InputRequestMapper;
import qg.qgent.mapper.TaskExecutionArtifactMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.TaskStepMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
    private final ProjectAccessService projectAccess;
    private final EventService eventService;
    private final NotificationService notificationService;

    public TaskRunService(TaskRunMapper taskRunMapper,
            ExecutionLogMapper logMapper, InputRequestMapper inputRequestMapper, DiffMapper diffMapper,
            TaskStepMapper taskStepMapper, AgentMapper agentMapper, TaskExecutionArtifactMapper artifactMapper,
            ProjectAccessService projectAccess, EventService eventService,
            NotificationService notificationService) {
        this.taskRunMapper = taskRunMapper;
        this.logMapper = logMapper;
        this.inputRequestMapper = inputRequestMapper;
        this.diffMapper = diffMapper;
        this.taskStepMapper = taskStepMapper;
        this.agentMapper = agentMapper;
        this.artifactMapper = artifactMapper;
        this.projectAccess = projectAccess;
        this.eventService = eventService;
        this.notificationService = notificationService;
    }

    /**
     * Lists immutable execution attempts belonging to the confirmed top-level task.
     * 列表项补充可读摘要（步骤标题、Agent、状态摘要、等待/失败原因与执行时间），
     * 步骤与 Agent 一次性批量加载，避免逐条运行查询。
     */
    public ApiPageResponse<TaskRunListItemResponse> listByTask(UUID projectId, UUID taskId, UUID userId,
            String cursor, int limit, String requestId) {
        projectAccess.requireProjectMember(projectId, userId);
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
        List<InputRequestEntity> requests = inputRequestMapper.selectList(
                Wrappers.<InputRequestEntity>lambdaQuery().eq(InputRequestEntity::getTaskRunId, run.getId()));
        return new TaskRunDetailResponse(
                id(run.getId()), id(run.getProjectId()), id(run.getTaskId()), id(run.getTaskStepId()),
                id(run.getAgentId()),
                run.getRole(), run.getStatus(), id(run.getRetryOfTaskRunId()),
                statusReason(run, requests),
                artifactSummary(run.getId()), iso(run.getStartedAt()), iso(run.getFinishedAt()),
                durationMs(run.getStartedAt(), run.getFinishedAt()), iso(run.getCreatedAt()),
                iso(run.getUpdatedAt()));
    }

    /**
     * 为 FAILED/CANCELLED/BLOCKED 的运行创建一次新的 TaskRun（retryOfTaskRunId 指向原运行），
     * 新运行置为 QUEUED 并初始化 PENDING 步骤；返回 202 受理。
     */
    @Transactional
    public TaskRunSummaryResponse retry(UUID projectId, UUID taskRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskRunEntity source = requireRun(projectId, taskRunId);
        requireOwner(source, projectId, userId);
        if (!RETRYABLE.contains(source.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_RUN_NOT_RETRYABLE", "仅 FAILED/CANCELLED/BLOCKED 状态可重试");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TaskRunEntity run = new TaskRunEntity();
        run.setId(UuidV7.next());
        run.setProjectId(projectId);
        run.setTaskId(source.getTaskId());
        run.setTaskStepId(source.getTaskStepId());
        run.setAgentId(source.getAgentId());
        run.setRole(source.getRole());
        run.setStatus("QUEUED");
        run.setRetryOfTaskRunId(source.getId());
        // 重试复用同一分支上下文，Workspace/Sandbox 由新执行会话重新分配
        run.setCreatedBy(userId);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        taskRunMapper.insert(run);
        eventService.publish(projectId, null, "task-run.updated", run.getId().toString(),
                eventPayload(run, 0));
        return toSummary(run);
    }

    /**
     * 取消未完成运行：QUEUED 直接置 CANCELLED；RUNNING/WAITING_INPUT/WAITING_APPROVAL/BLOCKED 置
     * CANCELLING
     * （真实终止由执行器接缝完成）；SUCCEEDED/FAILED/CANCELLED/CANCELLING 不可取消。
     */
    @Transactional
    public TaskRunSummaryResponse cancel(UUID projectId, UUID taskRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskRunEntity run = requireRun(projectId, taskRunId);
        requireOwner(run, projectId, userId);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if ("QUEUED".equals(run.getStatus())) {
            run.setStatus("CANCELLED");
            run.setFinishedAt(now);
            run.setUpdatedAt(now);
            taskRunMapper.updateById(run);
        } else if (CANCELLABLE_RUNNING.contains(run.getStatus())) {
            // 真实终止由执行器接缝在安全检查点完成，此处仅受理并标记
            run.setStatus("CANCELLING");
            run.setUpdatedAt(now);
            taskRunMapper.updateById(run);
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
        requireRun(projectId, taskRunId);
        int size = clampLimit(limit);
        long after = parseLongCursor(cursor);
        List<ExecutionLogEntity> rows = logMapper.selectList(Wrappers.<ExecutionLogEntity>lambdaQuery()
                .eq(ExecutionLogEntity::getTaskRunId, taskRunId)
                .gt(ExecutionLogEntity::getSequenceNo, after)
                .orderByAsc(ExecutionLogEntity::getSequenceNo)
                .last("LIMIT " + (size + 1)));
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
        // 仅返回只读摘要；宿主机路径、容器控制入口与凭据一律不返回
        return new ExecutionContextResponse(iso(run.getStartedAt()), null);
    }

    /**
     * 查询运行期间发起的人机输入/审批请求。
     */
    public List<InputRequestResponse> inputRequests(UUID projectId, UUID taskRunId, UUID userId) {
        projectAccess.requireProjectMember(projectId, userId);
        requireRun(projectId, taskRunId);
        return inputRequestMapper.selectList(Wrappers.<InputRequestEntity>lambdaQuery()
                .eq(InputRequestEntity::getTaskRunId, taskRunId).orderByAsc(InputRequestEntity::getCreatedAt))
                .stream().map(this::toInput).toList();
    }

    /**
     * 回答 WAITING_INPUT 输入请求，回答后运行恢复 RUNNING。
     */
    @Transactional
    public InputRequestResponse replyInput(UUID projectId, UUID taskRunId, UUID requestId, UUID userId,
            Map<String, Object> answer) {
        projectAccess.requireProjectMember(projectId, userId);
        TaskRunEntity run = requireRun(projectId, taskRunId);
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

    /** QUEUED → RUNNING，记录开始时间；仅 QUEUED 状态可开始。 */
    @Transactional
    public void markRunning(UUID taskRunId) {
        TaskRunEntity run = taskRunMapper.selectById(taskRunId);
        if (run == null || !"QUEUED".equals(run.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_RUN_NOT_STARTABLE", "仅 QUEUED 运行可开始");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        run.setStatus("RUNNING");
        run.setStartedAt(now);
        run.setUpdatedAt(now);
        taskRunMapper.updateById(run);
        eventService.publish(run.getProjectId(), null, "task-run.updated", run.getId().toString(), eventPayload(run, 0));
    }

    /**
     * RUNNING → 终态（SUCCEEDED/FAILED/CANCELLED），记录结束时间并发布事件。
     * 属于受控执行接缝：状态由确定性 Orchestrator 依据 Agent 结果映射，本方法不自行判断。
     */
    @Transactional
    public void complete(UUID taskRunId, String terminalStatus) {
        if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED").contains(terminalStatus)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_RUN_TERMINAL_STATUS", "非法运行终态");
        }
        TaskRunEntity run = taskRunMapper.selectById(taskRunId);
        if (run == null || !"RUNNING".equals(run.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "TASK_RUN_NOT_COMPLETABLE", "仅 RUNNING 运行可完成");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        run.setStatus(terminalStatus);
        run.setFinishedAt(now);
        run.setUpdatedAt(now);
        taskRunMapper.updateById(run);
        eventService.publish(run.getProjectId(), null, "task-run.updated", run.getId().toString(), eventPayload(run, 0));
    }

    /** 批准/拒绝 WAITING_APPROVAL 请求：批准恢复 RUNNING，拒绝进入 BLOCKED。 */
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
        eventService.publish(run.getProjectId(), null, "task-run.updated",
                run.getId().toString(), eventPayload(run, 0));
        return toInput(req);
    }

    // ---------- 私有辅助 ----------

    /** 加载运行并校验其归属路径项目，防止跨项目仅凭 UUID 查询。 */
    private TaskRunEntity requireRun(UUID projectId, UUID taskRunId) {
        TaskRunEntity run = taskRunMapper.selectById(taskRunId);
        if (run == null || !run.getProjectId().equals(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TASK_RUN_NOT_FOUND", "任务运行不存在或不可见");
        }
        return run;
    }

    /** 发起人或 Project Admin 才允许操作，否则 403。 */
    private void requireOwner(TaskRunEntity run, UUID projectId, UUID userId) {
        if (!projectAccess.isOwnerOrAdmin(run.getCreatedBy(), projectId, userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "TASK_RUN_FORBIDDEN", "仅发起人或 Project Admin 可操作该运行");
        }
    }

    /** 加载输入请求并校验其归属于该运行。 */
    private InputRequestEntity requireInput(TaskRunEntity run, UUID requestId) {
        InputRequestEntity req = inputRequestMapper.selectById(requestId);
        if (req == null || !run.getId().equals(req.getTaskRunId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "INPUT_REQUEST_NOT_FOUND", "输入请求不存在或不可见");
        }
        return req;
    }

    /** 该运行自身产出的产物与 Diff 数量摘要（total=执行产物数，diffCount=总 Diff 数）。 */
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

    /** 批量构造任务运行列表项；步骤、Agent、输入请求、产物与 Diff 一次性加载，避免逐条运行 N+1。 */
    private List<TaskRunListItemResponse> buildListItems(List<TaskRunEntity> page) {
        List<UUID> runIds = page.stream().map(TaskRunEntity::getId).toList();
        Set<UUID> stepIds = page.stream().map(TaskRunEntity::getTaskStepId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<UUID> agentIds = page.stream().map(TaskRunEntity::getAgentId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // 空 Map 用 emptyMap 保证 null 键查找返回 null（TaskRun.agentId 可为 null，Map.of() 的 get(null) 会抛 NPE）
        Map<UUID, TaskStepEntity> stepById = stepIds.isEmpty() ? Collections.emptyMap()
                : taskStepMapper.selectList(Wrappers.<TaskStepEntity>lambdaQuery().in(TaskStepEntity::getId, stepIds))
                        .stream().collect(Collectors.toMap(TaskStepEntity::getId, Function.identity()));
        Map<UUID, AgentEntity> agentById = agentIds.isEmpty() ? Collections.emptyMap()
                : agentMapper.selectList(Wrappers.<AgentEntity>lambdaQuery().in(AgentEntity::getId, agentIds)).stream()
                        .collect(Collectors.toMap(AgentEntity::getId, Function.identity()));
        Map<UUID, List<InputRequestEntity>> inputByRun = runIds.isEmpty() ? Collections.emptyMap()
                : inputRequestMapper
                        .selectList(Wrappers.<InputRequestEntity>lambdaQuery()
                                .in(InputRequestEntity::getTaskRunId, runIds))
                        .stream().collect(Collectors.groupingBy(InputRequestEntity::getTaskRunId));
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
                agentById.get(run.getAgentId()), inputByRun.getOrDefault(run.getId(), List.of()),
                artifactCountByRun.getOrDefault(run.getId(), 0L), diffCountByRun.getOrDefault(run.getId(), 0L)))
                .toList();
    }

    private TaskRunListItemResponse toListItem(TaskRunEntity run, TaskStepEntity step, AgentEntity agent,
            List<InputRequestEntity> requests, long artifactTotal, long diffCount) {
        Map<String, Object> artifactSummary = new LinkedHashMap<>();
        artifactSummary.put("total", artifactTotal);
        artifactSummary.put("diffCount", diffCount);
        return new TaskRunListItemResponse(id(run.getId()), id(run.getTaskId()), id(run.getTaskStepId()),
                step == null ? null : step.getTitle(), run.getRole(), agentSummary(agent), run.getStatus(),
                statusSummary(run.getStatus()), statusReason(run, requests), id(run.getRetryOfTaskRunId()),
                iso(run.getStartedAt()), iso(run.getFinishedAt()),
                durationMs(run.getStartedAt(), run.getFinishedAt()), artifactSummary, iso(run.getCreatedAt()),
                iso(run.getUpdatedAt()));
    }

    /** 等待/阻塞/失败原因摘要，仅返回脱敏用户可见文本；无等待或失败时返回 null。 */
    private TaskStatusReason statusReason(TaskRunEntity run, List<InputRequestEntity> requests) {
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
            case "FAILED" -> new TaskStatusReason("EXECUTION_FAILED", "执行失败", "任务运行执行失败，可查看执行记录",
                    true, iso(run.getUpdatedAt()));
            case "CANCELLING", "CANCELLED" -> new TaskStatusReason("CANCELLED", "已取消", "任务运行已取消", false,
                    iso(run.getUpdatedAt()));
            default -> null;
        };
    }

    /** 脱敏状态摘要：不返回日志原文、Prompt、Token 或环境变量。 */
    private String statusSummary(String status) {
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

    /** 计算执行耗时（毫秒）；任一端时间为空或结束早于开始时返回 null，避免负值误导前端。 */
    private Long durationMs(LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (startedAt == null || finishedAt == null || finishedAt.isBefore(startedAt)) {
            return null;
        }
        return java.time.Duration.between(startedAt, finishedAt).toMillis();
    }

    /** 构造 task-run.updated 事件载荷；sequence 为运行内执行序号，状态事件暂无步骤序号填 0。 */
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
        return new LogEntryResponse(id(l.getId()), l.getSequenceNo(), l.getNode(), l.getContent(),
                iso(l.getCreatedAt()));
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
