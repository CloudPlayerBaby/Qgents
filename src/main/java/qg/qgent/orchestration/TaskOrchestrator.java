package qg.qgent.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.dto.TaskRepositoryScopeRequest;
import qg.qgent.dto.TaskStepCreateRequest;
import qg.qgent.entity.AgentEntity;
import qg.qgent.entity.ProjectEntity;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.AgentMapper;
import qg.qgent.mapper.ProjectMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskStepMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.orchestration.worker.SandboxSessionManager;
import qg.qgent.service.EventService;
import qg.qgent.service.FinalDiffBundleService;
import qg.qgent.service.MessageService;
import qg.qgent.service.NotificationService;
import qg.qgent.service.TaskEventPayloads;
import qg.qgent.service.TaskExecutionArtifactService;
import qg.qgent.service.TaskRunService;
import qg.qgent.service.TaskService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 确定性任务编排器：基于 LangGraph4j {@link StateGraph} 驱动 Task 从 PLAN 到
 * SUCCESS/FAILED/CANCELLED 的完整链路。不调用 LLM，只负责状态判断、Agent 调度、
 * TaskRun 生命周期、结果路由、Test/Review 失败后的 Coding 重试、循环上限与基础设施重试。
 * <p>
 * 编排图含 plan/coding/test/review 四个节点，节点间用条件边路由：每个节点执行后依据
 * {@link OrchestrationStateMachine#decide} 的决策在图中推进（ADVANCE）、同相位重试
 * （RETRY_PHASE）、回 Coding 修复（REQUEUE_CODING）或进入终态（END）。
 * <p>
 * 序列化边界：LangGraph4j 默认状态序列化走 Java ObjectStream，而 {@link AgentState}
 * 不实现 Serializable，因此图状态只承载可序列化基本值（projectId/taskId 用于定位执行
 * 现场、route 用于条件边路由）；富结果、循环反馈与计数放进程内 {@link TaskExecutionContext}，
 * 按 taskId 暂存，一次 orchestrate 结束后清理。
 *
 * Phase 1 约束（方案 B）：PLAN 相位不创建 TaskRun（task_runs.task_step_id NOT NULL，
 * 计划产出前没有步骤可挂），由本类内联执行 Plan Agent 并把 PlanResult 经 TaskService.addSteps
 * 落为 DEVELOPER/TESTER/REVIEWER 三个步骤；CODING/TESTING/REVIEWING 各创建真实 TaskRun。
 */
@Service
public class TaskOrchestrator {
    private static final Set<String> STARTABLE_TASK_STATUSES = Set.of("PLANNING", "PENDING", "RUNNING");
    /** 条件边路由：route 值 → 下一节点名；终态路由到 {@link GraphDefinition#END}。 */
    private static final Map<String, String> ROUTES = Map.of(
            "plan", "plan", "coding", "coding", "test", "test", "review", "review",
            GraphDefinition.END, GraphDefinition.END);

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);
    /** 终态中等待用户确认的 Diff 审核状态；确认后的交付由 DiffReviewBatchService 驱动。 */
    private static final String WAITING_DIFF_CONFIRMATION = "WAITING_DIFF_CONFIRMATION";

    private final OrchestrationStateMachine stateMachine;
    private final StepScheduler stepScheduler;
    private final AgentRunExecutor agentRunExecutor;
    private final AgentContextAssembler contextAssembler;
    private final TaskService taskService;
    private final TaskRunService taskRunService;
    private final TaskMapper taskMapper;
    private final TaskStepMapper stepMapper;
    private final WorkspaceRepositoryMapper workspaceRepositoryMapper;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final SandboxSessionManager sandboxSessionManager;
    private final TaskExecutionArtifactService artifactService;
    private final FinalDiffBundleService finalDiffBundles;
    private final MessageService messageService;
    private final AgentMapper agentMapper;
    private final ProjectMapper projectMapper;

    /** 各编排任务的执行现场（富结果/反馈/计数，不进图状态），按 taskId 暂存。 */
    private final Map<UUID, TaskExecutionContext> executions = new ConcurrentHashMap<>();

    private final CompiledGraph<TaskOrchestrationState> graph;

    public TaskOrchestrator(OrchestrationStateMachine stateMachine, StepScheduler stepScheduler,
            AgentRunExecutor agentRunExecutor, AgentContextAssembler contextAssembler, TaskService taskService,
            TaskRunService taskRunService, TaskMapper taskMapper, TaskStepMapper stepMapper,
            WorkspaceRepositoryMapper workspaceRepositoryMapper, EventService eventService,
            NotificationService notificationService, SandboxSessionManager sandboxSessionManager,
            TaskExecutionArtifactService artifactService, FinalDiffBundleService finalDiffBundles,
            MessageService messageService, AgentMapper agentMapper, ProjectMapper projectMapper) {
        this.stateMachine = stateMachine;
        this.stepScheduler = stepScheduler;
        this.agentRunExecutor = agentRunExecutor;
        this.contextAssembler = contextAssembler;
        this.taskService = taskService;
        this.taskRunService = taskRunService;
        this.taskMapper = taskMapper;
        this.stepMapper = stepMapper;
        this.workspaceRepositoryMapper = workspaceRepositoryMapper;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.sandboxSessionManager = sandboxSessionManager;
        this.artifactService = artifactService;
        this.finalDiffBundles = finalDiffBundles;
        this.messageService = messageService;
        this.agentMapper = agentMapper;
        this.projectMapper = projectMapper;
        this.graph = buildGraph();
    }

    /**
     * 同步驱动一个 Task 的完整编排链路：从 START 进入 plan 节点并沿条件边执行，
     * 直到任一节点决策到达终态（END）。整条链路同步跑完。
     * <p>
     * 入口为整条链路准备一次 Sandbox 会话（Worker 端口启用时），并在终态后释放；
     * 未启用 Worker 时会话管理器为 no-op，不影响本地端口链路。
     *
     * @param projectId 项目 ID（Task 归属校验）
     * @param taskId    要编排的 Task ID
     * @throws IllegalStateException Task 不存在、不属于该项目或状态不可启动时抛出
     */
    public void orchestrate(UUID projectId, UUID taskId) {
        TaskEntity task = requireTask(projectId, taskId);
        requireStartable(task);
        TaskExecutionContext ctx = new TaskExecutionContext(task);
        executions.put(taskId, ctx);
        try {
            sandboxSessionManager.acquire(task.getId(), task.getProjectId(), task.getWorkspaceId());
            graph.invoke(Map.of("projectId", projectId.toString(), "taskId", taskId.toString()));
        } finally {
            sandboxSessionManager.release(task.getWorkspaceId());
            executions.remove(taskId);
        }
    }

    /** 构建 plan/coding/test/review 四节点 + 条件边路由的编排图。 */
    private CompiledGraph<TaskOrchestrationState> buildGraph() {
        try {
            StateGraph<TaskOrchestrationState> g = new StateGraph<>(TaskOrchestrationState::new);
            g.addNode("plan", AsyncNodeAction.node_async(
                    (TaskOrchestrationState s) -> runPhaseNode(OrchestrationPhase.PLAN, s)));
            g.addNode("coding", AsyncNodeAction.node_async(
                    (TaskOrchestrationState s) -> runPhaseNode(OrchestrationPhase.CODING, s)));
            g.addNode("test", AsyncNodeAction.node_async(
                    (TaskOrchestrationState s) -> runPhaseNode(OrchestrationPhase.TESTING, s)));
            g.addNode("review", AsyncNodeAction.node_async(
                    (TaskOrchestrationState s) -> runPhaseNode(OrchestrationPhase.REVIEWING, s)));
            AsyncEdgeAction<TaskOrchestrationState> route = AsyncEdgeAction.edge_async(
                    (TaskOrchestrationState s) -> s.<String>value("route").orElse(GraphDefinition.END));
            for (String node : List.of("plan", "coding", "test", "review")) {
                g.addConditionalEdges(node, route, ROUTES);
            }
            g.addEdge(GraphDefinition.START, "plan");
            // 循环上限远高于状态机自身的质量/基础设施重试上限，避免框架先于业务计数终止。
            return g.compile(CompileConfig.builder().recursionLimit(64).build());
        } catch (GraphStateException e) {
            // 图结构错误属于编程错误（节点/边名不匹配），包装为运行时异常，不改变构造器签名。
            throw new IllegalStateException("failed to build orchestration graph", e);
        }
    }

    /**
     * 执行一个编排节点：PLAN 内联执行（无 TaskRun），其余相位创建 TaskRun 并推进，
     * 依据状态机决策在图中路由下一步，返回仅含可序列化基本值的图状态。
     */
    private Map<String, Object> runPhaseNode(OrchestrationPhase phase, TaskOrchestrationState state) {
        TaskExecutionContext ctx = executions.get(state.getTaskId());
        TaskEntity task = ctx.task;
        PhaseRun result = phase == OrchestrationPhase.PLAN
                ? new PhaseRun(safeExecute(phase, contextAssembler.assemblePlan(task)), null)
                : executeStep(task, phase, ctx);
        AgentRunOutcome outcome = result.outcome;
        if (result.runId != null) {
            ctx.lastRunId = result.runId;
        }
        if (phase == OrchestrationPhase.PLAN && outcome.getPlanResult() != null) {
            ctx.planResult = outcome.getPlanResult();
        } else if (phase == OrchestrationPhase.CODING && outcome.getCodingResult() != null) {
            ctx.codingResult = outcome.getCodingResult();
        } else if (phase == OrchestrationPhase.TESTING && outcome.getTestResult() != null) {
            ctx.testResult = outcome.getTestResult();
        }
        StateMachineDecision decision = stateMachine.decide(phase, outcome.getOutcome(), ctx.counters);
        String route;
        switch (decision.getAction()) {
            case ADVANCE -> {
                if (phase == OrchestrationPhase.PLAN) {
                    persistPlanSteps(task, outcome.getPlanResult());
                    updateTaskStatus(task, "RUNNING");
                }
                ctx.feedback = null;
                ctx.retryOf = null;
                route = nodeName(decision.getNextPhase());
            }
            case REQUEUE_CODING -> {
                ctx.feedback = outcome;
                ctx.retryOf = ctx.lastRunId;
                route = nodeName(decision.getNextPhase());
            }
            case RETRY_PHASE -> {
                ctx.feedback = null;
                ctx.retryOf = ctx.lastRunId;
                route = nodeName(decision.getNextPhase());
            }
            default -> {
                finishTask(task, ctx, decision.getAction());
                route = GraphDefinition.END;
            }
        }
        return Map.of("projectId", projectId(task), "taskId", taskId(task), "route", route);
    }

    /** 执行 CODING/TESTING/REVIEWING 相位：定位步骤、创建 TaskRun、执行并落终态。 */
    private PhaseRun executeStep(TaskEntity task, OrchestrationPhase phase, TaskExecutionContext ctx) {
        TaskStepEntity step = stepScheduler.findStepForPhase(task.getId(), phase);
        markStepRunning(task, step);
        TaskRunEntity run = taskRunService.createForStep(task.getProjectId(), task.getId(), step.getId(),
                phase.role(), step.getAssignedAgentId(), task.getCreatedBy(), ctx.retryOf);
        taskRunService.markRunning(run.getId());
        AgentInput input = contextAssembler.assemble(task, step, phase, ctx.feedback, run.getId(), ctx.planResult,
                ctx.codingResult, ctx.testResult);
        AgentRunOutcome outcome = safeExecute(phase, input);
        if (phase == OrchestrationPhase.CODING && outcome.getOutcome() == RunOutcome.SUCCEEDED) {
            ctx.lastCodingRunId = run.getId();
            ctx.lastCodingAgentId = step.getAssignedAgentId();
        }
        // AGENTS.md：Run 产物必须先成功落库，再发布 Run 终态事件
        artifactService.createRunArtifact(task, run, step, phase.name(), runArtifactSummary(phase, outcome));
        taskRunService.complete(run.getId(), terminalStatus(outcome.getOutcome()));
        markStepSettled(task, step, outcome.getOutcome());
        return new PhaseRun(outcome, run.getId());
    }

    private String projectId(TaskEntity task) {
        return task.getProjectId().toString();
    }

    private String taskId(TaskEntity task) {
        return task.getId().toString();
    }

    private String nodeName(OrchestrationPhase phase) {
        return switch (phase) {
            case PLAN -> "plan";
            case CODING -> "coding";
            case TESTING -> "test";
            case REVIEWING -> "review";
        };
    }

    /** Agent 抛异常统一按基础设施失败处理，避免异常破坏状态机推进。 */
    private AgentRunOutcome safeExecute(OrchestrationPhase phase, AgentInput input) {
        try {
            return agentRunExecutor.execute(phase, input);
        } catch (RuntimeException e) {
            AgentRunOutcome failure = new AgentRunOutcome();
            failure.setPhase(phase);
            failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
            failure.setMessage("agent execution failed: " + e.getMessage());
            return failure;
        }
    }

    private String terminalStatus(RunOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> "SUCCEEDED";
            case CANCELLED -> "CANCELLED";
            default -> "FAILED";
        };
    }

    /** Run 级执行产物的脱敏摘要：角色、终态与 Agent 反馈消息，路径与敏感键由服务端 sanitize 兜底。 */
    private Map<String, Object> runArtifactSummary(OrchestrationPhase phase, AgentRunOutcome outcome) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("role", phase.role());
        summary.put("status", terminalStatus(outcome.getOutcome()));
        summary.put("message", outcome.getMessage());
        return summary;
    }

    /** 把 PlanResult 落为 DEVELOPER → TESTER → REVIEWER 三个依赖链步骤，并按角色分配团队内 ACTIVE Agent。 */
    private void persistPlanSteps(TaskEntity task, PlanResult plan) {
        List<UUID> repoIds = workspaceRepositoryMapper.selectByWorkspace(task.getWorkspaceId()).stream()
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId).toList();
        UUID devId = UuidV7.next();
        UUID testId = UuidV7.next();
        UUID reviewId = UuidV7.next();
        List<TaskStepCreateRequest> requests = List.of(
                planStep(devId, "Implement", planInstruction(plan), "DEVELOPER", resolveAgent(task, "DEVELOPER"),
                        List.of(), "WRITE", repoIds),
                planStep(testId, "Verify", plan.getTestPlan(), "TESTER", resolveAgent(task, "TESTER"),
                        List.of(devId), "READ", repoIds),
                planStep(reviewId, "Review", "审查本次改动是否符合需求、质量与安全要求", "REVIEWER",
                        resolveAgent(task, "REVIEWER"), List.of(testId), "READ", repoIds));
        taskService.addSteps(task.getProjectId(), task.getId(), task.getCreatedBy(), requests);
        // PLAN 产物只属于 Task，不关联 TaskRun/TaskStep（AGENTS.md）
        artifactService.createPlan(task, planSummary(plan));
    }

    /**
     * 方案 A：按角色在团队内解析 ACTIVE Agent（优先 TEAM 可见；PRIVATE 仅限任务发起人，与
     * TaskService.validateAgent 的校验条件一致），按名称升序取第一个；查不到时返回 null，
     * 该步骤不回群（兜底约定，不使任务失败）。
     */
    private UUID resolveAgent(TaskEntity task, String role) {
        ProjectEntity project = projectMapper.selectById(task.getProjectId());
        if (project == null || project.getTeamId() == null) {
            return null;
        }
        List<AgentEntity> candidates = agentMapper.selectList(Wrappers.<AgentEntity>lambdaQuery()
                .eq(AgentEntity::getTeamId, project.getTeamId())
                .eq(AgentEntity::getRole, role)
                .eq(AgentEntity::getStatus, "ACTIVE")
                .and(visibility -> visibility.eq(AgentEntity::getVisibility, "TEAM")
                        .or(owner -> owner.eq(AgentEntity::getVisibility, "PRIVATE")
                                .eq(AgentEntity::getCreatedBy, task.getCreatedBy())))
                .orderByAsc(AgentEntity::getName));
        return candidates.isEmpty() ? null : candidates.get(0).getId();
    }

    private TaskStepCreateRequest planStep(UUID id, String title, String instruction, String role, UUID agentId,
            List<UUID> dependencyIds, String accessMode, List<UUID> repoIds) {
        TaskStepCreateRequest request = new TaskStepCreateRequest();
        request.setId(id);
        request.setTitle(title);
        request.setInstruction(instruction);
        request.setRole(role);
        request.setAssignedAgentId(agentId);
        request.setAcceptanceCriteria("满足该步骤验收条件");
        request.setDependencyIds(dependencyIds);
        request.setRepositoryScopes(repoIds.stream().map(repositoryId -> {
            TaskRepositoryScopeRequest scope = new TaskRepositoryScopeRequest();
            scope.setRepositoryId(repositoryId);
            scope.setAccessMode(accessMode);
            return scope;
        }).toList());
        return request;
    }

    /** PLAN 产物的用户可见摘要：目标与实现步骤标题，不落完整指令与文件路径明细。 */
    private Map<String, Object> planSummary(PlanResult plan) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("objectives", plan.getObjectives());
        summary.put("steps", plan.getImplementationSteps().stream()
                .map(PlanResult.ImplementationStep::getTitle).toList());
        return summary;
    }

    private String planInstruction(PlanResult plan) {
        StringBuilder sb = new StringBuilder("实现目标：").append(String.join("；", plan.getObjectives()));
        for (PlanResult.ImplementationStep step : plan.getImplementationSteps()) {
            sb.append("\n- ").append(step.getTitle()).append(" 文件：")
                    .append(String.join(",", step.getFiles()));
        }
        if (plan.getTestPlan() != null && !plan.getTestPlan().isBlank()) {
            sb.append("\n测试计划：").append(plan.getTestPlan());
        }
        if (plan.getRisks() != null && !plan.getRisks().isEmpty()) {
            sb.append("\n风险：").append(String.join("；", plan.getRisks()));
        }
        return sb.toString();
    }

    private void markStepRunning(TaskEntity task, TaskStepEntity step) {
        step.setStatus("RUNNING");
        step.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        stepMapper.updateById(step);
        publishStepUpdated(task, step);
    }

    private void markStepSettled(TaskEntity task, TaskStepEntity step, RunOutcome outcome) {
        step.setStatus(outcome == RunOutcome.SUCCEEDED ? "SUCCEEDED" : "FAILED");
        step.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        stepMapper.updateById(step);
        publishStepUpdated(task, step);
        sendAgentCard(task, step.getAssignedAgentId(), "step-" + step.getId(), step.getStatus(), step.getRole(),
                stepSettledMessage(step));
    }

    private void publishStepUpdated(TaskEntity task, TaskStepEntity step) {
        eventService.publish(task.getProjectId(), task.getRequirementGroupId(), "task-step.updated",
                step.getId().toString(), TaskEventPayloads.taskStepUpdated(task.getProjectId(), step));
    }

    /**
     * 任务到达终态：成功路径先创建待确认 Diff 批次再置 WAITING_DIFF_CONFIRMATION（失败降级 SUCCEEDED），
     * 其余按取消/失败落终态；随后以编码 Agent 身份把任务结果卡片回群（失败不阻断编排）。
     */
    private void finishTask(TaskEntity task, TaskExecutionContext ctx, StateMachineDecision.Action action) {
        String status = switch (action) {
            case COMPLETE_SUCCESS -> completeWithDiffBatch(task, ctx);
            case COMPLETE_CANCELLED -> "CANCELLED";
            default -> "FAILED";
        };
        updateTaskStatus(task, status);
        sendAgentCard(task, ctx.lastCodingAgentId, "task-" + task.getId(), status, null, taskResultMessage(status));
    }

    /** 成功终态：生成待用户确认的 Diff 批次；Worker 不可用或无未提交改动时降级为 SUCCEEDED（等同旧行为）。 */
    private String completeWithDiffBatch(TaskEntity task, TaskExecutionContext ctx) {
        try {
            finalDiffBundles.createPendingBatch(task.getProjectId(), task.getId(), ctx.lastCodingRunId);
            return WAITING_DIFF_CONFIRMATION;
        } catch (RuntimeException e) {
            log.warn("final diff batch creation skipped, task finishes SUCCEEDED, taskId={}: {}",
                    task.getId(), e.getMessage());
            return "SUCCEEDED";
        }
    }

    /**
     * 以分配 Agent 身份把 TASK_STATUS 卡片回群；agentId 为 null（查不到匹配 Agent）或发送失败时
     * 记日志并跳过，回群失败不等于任务失败（与 TaskExecutionListener 吞异常模式一致）。
     */
    private void sendAgentCard(TaskEntity task, UUID agentId, String idSuffix, String status, String node,
            String message) {
        if (agentId == null) {
            log.warn("agent card skipped (no assigned agent), taskId={}, suffix={}", task.getId(), idSuffix);
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("taskId", task.getId().toString());
        content.put("status", status);
        if (node != null) {
            content.put("node", node);
        }
        if (message != null) {
            content.put("message", message);
        }
        MessageSendRequest body = new MessageSendRequest();
        body.setType("TASK_STATUS");
        body.setClientMessageId("agent-" + idSuffix + "-" + status);
        body.setContent(content);
        try {
            messageService.sendAsAgent(task.getRequirementGroupId(), agentId, body);
        } catch (RuntimeException e) {
            log.warn("agent card skipped, taskId={}, suffix={}: {}", task.getId(), idSuffix, e.getMessage());
        }
    }

    /** step 级卡片文案：以中文表达该角色步骤的完成/失败。 */
    private String stepSettledMessage(TaskStepEntity step) {
        return switch (step.getStatus()) {
            case "SUCCEEDED" -> roleLabel(step.getRole()) + "步骤已完成";
            default -> roleLabel(step.getRole()) + "步骤失败，已按重试或修复策略处理";
        };
    }

    /** 任务结果卡片文案：按终态表达交付确认、完成、失败或取消。 */
    private String taskResultMessage(String status) {
        return switch (status) {
            case WAITING_DIFF_CONFIRMATION -> "任务开发完成，等待你对 Diff 的确认";
            case "SUCCEEDED" -> "任务已完成";
            case "FAILED" -> "任务执行失败";
            case "CANCELLED" -> "任务已取消";
            default -> "任务状态更新：" + status;
        };
    }

    /** 角色英文 → 中文业务名，用于卡片文案；未识别角色原样返回。 */
    private String roleLabel(String role) {
        return switch (role) {
            case "DEVELOPER" -> "开发";
            case "TESTER" -> "测试";
            case "REVIEWER" -> "审查";
            default -> role;
        };
    }

    private void updateTaskStatus(TaskEntity task, String status) {
        task.setStatus(status);
        task.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        taskMapper.updateById(task);
        eventService.publish(task.getProjectId(), task.getRequirementGroupId(), "task.updated",
                task.getId().toString(), TaskEventPayloads.taskUpdated(task));
        notifyTaskTerminal(task, status);
    }

    /**
     * 任务到达终态时向发起人写入通知（A 联调约定 §1）。
     * 仅 SUCCEEDED/FAILED 有对应 kind；CANCELLED 不在约定映射内，不写入。
     */
    private void notifyTaskTerminal(TaskEntity task, String status) {
        String kind = switch (status) {
            case "SUCCEEDED" -> "TASK_COMPLETED";
            case "FAILED" -> "TASK_FAILED";
            default -> null;
        };
        if (kind == null) {
            return;
        }
        notificationService.notify(task.getCreatedBy(), task.getProjectId(), task.getRequirementGroupId(), kind,
                (kind.equals("TASK_COMPLETED") ? "任务完成：" : "任务失败：") + task.getTitle(), task.getRequirement(),
                task.getId().toString());
    }

    private TaskEntity requireTask(UUID projectId, UUID taskId) {
        TaskEntity task = taskMapper.selectById(taskId);
        if (task == null || !projectId.equals(task.getProjectId())) {
            throw new IllegalStateException("Task " + taskId + " not found in project " + projectId);
        }
        return task;
    }

    private void requireStartable(TaskEntity task) {
        if (!STARTABLE_TASK_STATUSES.contains(task.getStatus())) {
            throw new IllegalStateException("Task " + task.getId() + " is not startable from status " + task.getStatus());
        }
    }

    /** 一次相位执行的产物：Agent 结果 + 对应 TaskRun（PLAN 为 null）。 */
    private static final class PhaseRun {
        private final AgentRunOutcome outcome;
        private final UUID runId;

        private PhaseRun(AgentRunOutcome outcome, UUID runId) {
            this.outcome = outcome;
            this.runId = runId;
        }
    }

    /**
     * StateGraph 节点间传递的图状态视图。LangGraph4j 默认状态序列化走 Java ObjectStream，
     * 且 {@link AgentState} 不实现 Serializable，因此图状态只承载可序列化基本值：
     * projectId/taskId 用于定位执行现场，route 用于条件边路由。
     */
    private static final class TaskOrchestrationState extends AgentState {
        private TaskOrchestrationState(Map<String, Object> data) {
            super(data);
        }

        private UUID getProjectId() {
            return UUID.fromString(this.<String>value("projectId").orElse(""));
        }

        private UUID getTaskId() {
            return UUID.fromString(this.<String>value("taskId").orElse(""));
        }
    }

    /**
     * 一次 orchestrate 会话内的执行现场：跨节点传递的富结果、循环反馈、最近 TaskRun
     * 与循环计数。与图状态解耦，仅在进程内按 taskId 暂存，invoke 结束后由 orchestrate 清理。
     */
    private static final class TaskExecutionContext {
        private final TaskEntity task;
        private final OrchestrationCounters counters = new OrchestrationCounters();
        private AgentRunOutcome feedback;
        private UUID lastRunId;
        private UUID retryOf;
        /** 最后一次 SUCCEEDED 的 CODING run，终态时供 FinalDiffBundleService 生成待确认 Diff 批次。 */
        private UUID lastCodingRunId;
        /** 上述 coding run 的执行 Agent，作为任务结果卡片发言身份。 */
        private UUID lastCodingAgentId;
        private PlanResult planResult;
        private CodingResult codingResult;
        private TestResult testResult;

        private TaskExecutionContext(TaskEntity task) {
            this.task = task;
        }
    }
}
