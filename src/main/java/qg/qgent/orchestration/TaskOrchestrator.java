package qg.qgent.orchestration;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncEdgeAction;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Service;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.TaskRepositoryScopeRequest;
import qg.qgent.dto.TaskStepCreateRequest;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskStepMapper;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.orchestration.worker.SandboxSessionManager;
import qg.qgent.service.EventService;
import qg.qgent.service.NotificationService;
import qg.qgent.service.TaskEventPayloads;
import qg.qgent.service.TaskRunService;
import qg.qgent.service.TaskService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    /** 各编排任务的执行现场（富结果/反馈/计数，不进图状态），按 taskId 暂存。 */
    private final Map<UUID, TaskExecutionContext> executions = new ConcurrentHashMap<>();

    private final CompiledGraph<TaskOrchestrationState> graph;

    public TaskOrchestrator(OrchestrationStateMachine stateMachine, StepScheduler stepScheduler,
            AgentRunExecutor agentRunExecutor, AgentContextAssembler contextAssembler, TaskService taskService,
            TaskRunService taskRunService, TaskMapper taskMapper, TaskStepMapper stepMapper,
            WorkspaceRepositoryMapper workspaceRepositoryMapper, EventService eventService,
            NotificationService notificationService, SandboxSessionManager sandboxSessionManager) {
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
                finishTask(task, decision.getAction());
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
                phase.role(), null, task.getCreatedBy(), ctx.retryOf);
        taskRunService.markRunning(run.getId());
        AgentInput input = contextAssembler.assemble(task, step, phase, ctx.feedback, run.getId(), ctx.planResult,
                ctx.codingResult, ctx.testResult);
        AgentRunOutcome outcome = safeExecute(phase, input);
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

    /** 把 PlanResult 落为 DEVELOPER → TESTER → REVIEWER 三个依赖链步骤。 */
    private void persistPlanSteps(TaskEntity task, PlanResult plan) {
        List<UUID> repoIds = workspaceRepositoryMapper.selectByWorkspace(task.getWorkspaceId()).stream()
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId).toList();
        UUID devId = UuidV7.next();
        UUID testId = UuidV7.next();
        UUID reviewId = UuidV7.next();
        List<TaskStepCreateRequest> requests = List.of(
                planStep(devId, "Implement", planInstruction(plan), "DEVELOPER", List.of(), "WRITE", repoIds),
                planStep(testId, "Verify", plan.getTestPlan(), "TESTER", List.of(devId), "READ", repoIds),
                planStep(reviewId, "Review", "审查本次改动是否符合需求、质量与安全要求", "REVIEWER",
                        List.of(testId), "READ", repoIds));
        taskService.addSteps(task.getProjectId(), task.getId(), task.getCreatedBy(), requests);
    }

    private TaskStepCreateRequest planStep(UUID id, String title, String instruction, String role,
            List<UUID> dependencyIds, String accessMode, List<UUID> repoIds) {
        TaskStepCreateRequest request = new TaskStepCreateRequest();
        request.setId(id);
        request.setTitle(title);
        request.setInstruction(instruction);
        request.setRole(role);
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
    }

    private void publishStepUpdated(TaskEntity task, TaskStepEntity step) {
        eventService.publish(task.getProjectId(), task.getRequirementGroupId(), "task-step.updated",
                step.getId().toString(), TaskEventPayloads.taskStepUpdated(task.getProjectId(), step));
    }

    private void finishTask(TaskEntity task, StateMachineDecision.Action action) {
        String status = switch (action) {
            case COMPLETE_SUCCESS -> "SUCCEEDED";
            case COMPLETE_CANCELLED -> "CANCELLED";
            default -> "FAILED";
        };
        updateTaskStatus(task, status);
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
        private PlanResult planResult;
        private CodingResult codingResult;
        private TestResult testResult;

        private TaskExecutionContext(TaskEntity task) {
            this.task = task;
        }
    }
}
