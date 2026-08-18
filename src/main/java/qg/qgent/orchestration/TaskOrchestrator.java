package qg.qgent.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.dto.GroupContext;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;
import qg.qgent.orchestration.llm.LlmObservation;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
import qg.qgent.orchestration.result.ReviewResult;
import qg.qgent.orchestration.result.TestResult;
import qg.qgent.orchestration.worker.SandboxSessionManager;
import qg.qgent.service.*;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 确定性任务编排器：基于 LangGraph4j {@link StateGraph} 驱动 Task 从 PLAN 到
 * SUCCESS/FAILED/CANCELLED 的完整链路。不调用 LLM，只负责状态判断、Agent 调度、
 * TaskRun 生命周期、结果路由、Test/Review 失败后的 Coding 重试、循环上限与基础设施重试。
 * <p>
 * 编排图由 {@link WorkflowGraphBuilder} 按任务的步骤列表**数据驱动构建**：每个 TaskStep 一个
 * 节点（Planner bootstrap 成功后才物化的正式执行 step）执行 {@link #runStepNode}——建 TaskRun、
 * 经 {@link AgentRegistry} 解析 Agent（自定义 Agent 或内置兜底）执行、落产物、依据
 * {@link OrchestrationStateMachine#decide} 的决策在图中推进（next/retry/requeue/END）。
 * <p>
 * 序列化边界：LangGraph4j 默认状态序列化走 Java ObjectStream，而 {@code AgentState}
 * 不实现 Serializable，因此图状态只承载可序列化基本值（projectId/taskId 用于定位执行
 * 现场、route 用于条件边路由）；富结果、循环反馈与计数放进程内 {@link TaskExecutionContext}，
 * 按 taskId 暂存，一次 orchestrate 结束后清理。
 */
@Service
public class TaskOrchestrator {

    /**
     * 编排启动前允许的状态（PLANNING/PENDING 由 claimForOrchestration/claimForResume 认领到 RUNNING）；
     * RUNNING 保留在集合中供 failStartup 判断"任务是否仍处于可覆盖的启动态"——并发取消（CANCELLING/
     * CANCELLED）或已终态时不得覆盖。
     */
    private static final Set<String> STARTABLE_TASK_STATUSES = Set.of("PLANNING", "PENDING", "RUNNING");

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);
    /**
     * 终态中等待用户确认的 Diff 审核状态；确认后的交付由 DiffReviewBatchService 驱动。
     */
    private static final String WAITING_DIFF_CONFIRMATION = "WAITING_DIFF_CONFIRMATION";
    private static final String NO_CODE_CHANGES_MESSAGE = "任务已完成，但未检测到代码变更，因此没有生成 Diff 或 MR。";

    private final OrchestrationStateMachine stateMachine;
    private final WorkflowGraphBuilder workflowGraphBuilder;
    private final AgentRegistry agentRegistry;
    private final AgentContextAssembler contextAssembler;
    private final TaskRunService taskRunService;
    private final TaskMapper taskMapper;
    private final TaskStepMapper stepMapper;
    private final EventService eventService;
    private final NotificationService notificationService;
    private final SandboxSessionManager sandboxSessionManager;
    private final TaskExecutionArtifactService artifactService;
    private final FinalDiffBundleService finalDiffBundles;
    private final DiffMapper diffMapper;
    private final MessageService messageService;
    private final OrchestratorAgentService orchestratorAgents;
    private final TaskPlanMaterializationService planMaterialization;
    private final java.util.concurrent.ExecutorService taskRunTimeoutExecutor;
    private final OrchestrationTimeoutProperties orchestrationTimeout;

    /**
     * 各编排任务的执行现场（富结果/反馈/计数，不进图状态），按 taskId 暂存。
     */
    private final Map<UUID, TaskExecutionContext> executions = new ConcurrentHashMap<>();

    public TaskOrchestrator(OrchestrationStateMachine stateMachine, WorkflowGraphBuilder workflowGraphBuilder,
                            AgentRegistry agentRegistry, AgentContextAssembler contextAssembler,
                            TaskRunService taskRunService, TaskMapper taskMapper, TaskStepMapper stepMapper,
                            EventService eventService,
                            NotificationService notificationService, SandboxSessionManager sandboxSessionManager,
                            TaskExecutionArtifactService artifactService, FinalDiffBundleService finalDiffBundles,
                            DiffMapper diffMapper, MessageService messageService,
                            OrchestratorAgentService orchestratorAgents, TaskPlanMaterializationService planMaterialization,
                            java.util.concurrent.ExecutorService taskRunTimeoutExecutor, OrchestrationTimeoutProperties orchestrationTimeout) {
        this.stateMachine = stateMachine;
        this.workflowGraphBuilder = workflowGraphBuilder;
        this.agentRegistry = agentRegistry;
        this.contextAssembler = contextAssembler;
        this.taskRunService = taskRunService;
        this.taskMapper = taskMapper;
        this.stepMapper = stepMapper;
        this.eventService = eventService;
        this.notificationService = notificationService;
        this.sandboxSessionManager = sandboxSessionManager;
        this.artifactService = artifactService;
        this.finalDiffBundles = finalDiffBundles;
        this.diffMapper = diffMapper;
        this.messageService = messageService;
        this.orchestratorAgents = orchestratorAgents;
        this.planMaterialization = planMaterialization;
        this.taskRunTimeoutExecutor = taskRunTimeoutExecutor;
        this.orchestrationTimeout = orchestrationTimeout;
    }

    /**
     * 同步驱动一个 Task 的完整编排链路：先确保步骤就位（模板 + 用户配置合并，resolveAgent
     * 落 assignedAgentId），再按步骤数据驱动建图并沿条件边执行，直到任一节点决策到达终态
     * （END）。整条链路同步跑完。
     * <p>
     * 入口为整条链路准备一次 Sandbox 会话（Worker 端口启用时），并在终态后释放；
     * 未启用 Worker 时会话管理器为 no-op，不影响本地端口链路。
     * <p>
     * 并发防护：任务以 {@link TaskMapper#claimForOrchestration} 原子认领（PLANNING/PENDING →
     * RUNNING），认领失败说明已被并发编排/已到终态，直接拒绝，杜绝同一 Task 被反复调用。
     *
     * @param projectId 项目 ID（Task 归属校验）
     * @param taskId    要编排的 Task ID
     * @throws IllegalStateException Task 不存在、不属于该项目或状态不可启动时抛出
     */
    public void orchestrate(UUID projectId, UUID taskId) {
        orchestrate(projectId, taskId, null, null);
    }

    /**
     * 从指定步骤续跑的编排入口（失败步骤重试 / 崩溃恢复调度器使用）：
     * 与 {@link #orchestrate(UUID, UUID)} 相同，但用 {@link TaskMapper#claimForResume}
     * 认领（允许 FAILED 回到 RUNNING），且图从 startStepId 节点开始执行。
     * <p>
     * 从任意 step 开始上下文为空是安全的：PLAN 不需要前序；CODING 靠 feedback；
     * TESTING 靠文件树；REVIEWING 直接读 Workspace Git Diff，均不依赖前序结果对象。
     *
     * @param projectId  项目 ID
     * @param taskId     要续跑的 Task ID
     * @param startStepId 起始步骤 ID；null 等价于全量 {@link #orchestrate(UUID, UUID)}
     * @throws IllegalStateException 认领失败（任务 RUNNING 中、或已是终态）
     */
    public void orchestrate(UUID projectId, UUID taskId, UUID startStepId) {
        orchestrate(projectId, taskId, startStepId, null);
    }

    /**
     * 续跑编排入口（带重试来源）：{@code retryOfTaskRunId} 为续跑产生的首个 TaskRun 记录重试来源
     * （指向用户重试的那个 FAILED/CANCELLED/BLOCKED run），保证审计链可追溯；崩溃恢复时为 null。
     */
    public void orchestrate(UUID projectId, UUID taskId, UUID startStepId, UUID retryOfTaskRunId) {
        log.info("orchestrate start taskId={} projectId={} startStepId={} retryOfTaskRunId={}",
                taskId, projectId, startStepId, retryOfTaskRunId);
        TaskEntity task = requireTask(projectId, taskId);
        // 全新任务（无起始步骤且未物化）规划期保持 PLANNING，不在入口认领置 RUNNING；规划完成、
        // 步骤物化后由下方统一原子认领。已物化 / 续跑路径才在入口原子认领。
        boolean freshPlanning = startStepId == null && task.getPlanMaterializedAt() == null;
        if (!freshPlanning) {
            int claimed = startStepId == null
                    ? taskMapper.claimForOrchestration(projectId, taskId)
                    : taskMapper.claimForResume(projectId, taskId);
            if (claimed != 1) {
                throw new IllegalStateException("Task " + taskId + " already claimed or not startable (status="
                        + task.getStatus() + ")");
            }
        }
        TaskExecutionContext ctx = new TaskExecutionContext(task);
        // 续跑来源：首个 TaskRun 的 retryOfTaskRunId 指向被重试的失败运行
        ctx.retryOf = retryOfTaskRunId;
        // 进程内防重入必须保持在 sandbox acquire 之前：只有赢家到达 acquire，避免并发编排器
        // 双持同一 workspace session、输家 finally release 销毁赢家正在用的沙箱。
        TaskExecutionContext previous = executions.putIfAbsent(taskId, ctx);
        if (previous != null) {
            throw new IllegalStateException("Task " + taskId + " is already being orchestrated in this process");
        }
        try {
            sandboxSessionManager.acquire(task.getId(), task.getProjectId(), task.getWorkspaceId());
            // 群聊/Skill/Memory 上下文快照：一次 orchestrate 组装一次，跨节点复用（失败不阻断）
            ctx.groupContext = contextAssembler.buildGroupContext(task);
            TaskEntity current = taskMapper.selectById(taskId);
            if (current == null) {
                throw new IllegalStateException("Task disappeared after orchestration claim");
            }
            // 规划块对所有入口保留：freshPlanning 保持 PLANNING 直接规划；恢复续跑若崩溃在规划
            // 中途（planMaterializedAt 仍 null），认领后也需补跑规划。
            if (current.getPlanMaterializedAt() == null) {
                TaskStepEntity planner = planMaterialization.ensurePlannerStep(current);
                if (!runPlanBootstrap(current, planner, ctx)) {
                    return;
                }
            }
            if (freshPlanning) {
                // 规划完成（物化成功，任务仍 PLANNING）：统一原子认领 PLANNING→RUNNING。
                // 并发另一执行器已物化并认领 / 用户已取消等返回 0，放弃本次执行。
                int claimed = taskMapper.claimForOrchestration(projectId, taskId);
                if (claimed != 1) {
                    log.info("orchestrate plan claimed by concurrent executor, skip taskId={}", taskId);
                    return;
                }
                publishTaskRunningEvent(taskId);
            }
            List<TaskStepEntity> steps = loadSteps(taskId).stream()
                    .filter(step -> !"PLANNER".equals(step.getRole())).toList();
            if (steps.isEmpty()) {
                throw new IllegalStateException("materialized task has no executable steps");
            }
            ctx.steps = steps;
            String startNodeId = steps.stream().anyMatch(step -> step.getId().equals(startStepId))
                    ? startStepId.toString() : null;
            CompiledGraph<TaskOrchestrationState> graph = workflowGraphBuilder.build(steps,
                    (step, state) -> runStepNode(step, state), lastDeveloperNodeId(steps), startNodeId,
                    ctx.counters.getMaxInfraRetries(), ctx.counters.getMaxQualityFixLoops());
            log.info("orchestrate sandbox acquired taskId={}", taskId);
            graph.invoke(Map.of("projectId", projectId.toString(), "taskId", taskId.toString()));
            log.info("orchestrate graph completed taskId={}", taskId);
        } catch (RuntimeException e) {
            if (isWorkspaceWriteLeaseHeld(e)) {
                deferForWorkspaceWriteLease(task);
                return;
            }
            // 启动/图执行阶段的意外失败（Sandbox Worker 不可达、建图失败等）必须落 FAILED 终态并
            // 通知用户，不允许任务无声卡死在初始状态；requireTask/requireStartable 的幂等护栏
            // 异常在 try 之外，继续外抛由监听器吞掉。
            failStartup(task, e);
        } finally {
            // 只允许本次 Task 清理自己领取的会话。若 acquire 因另一个 Task 正持有同一
            // Workspace 而失败，不能在 finally 中误销毁对方的 Sandbox 或释放对方租约。
            sandboxSessionManager.release(task.getWorkspaceId(), task.getId());
            executions.remove(taskId);
        }
    }

    /**
     * 另一个 Task 正在修改同一 Workspace 时，当前 Task 不能启动第二个 Writer。保留为 PENDING
     * 交给恢复器在租约释放后续跑，而不是伪装成环境故障或失败；任务状态写入与回群卡片都不在
     * Worker/GitHub 外调事务中。
     */
    private void deferForWorkspaceWriteLease(TaskEntity task) {
        if (taskMapper.deferForWorkspaceWriteLease(task.getProjectId(), task.getId()) != 1) {
            return;
        }
        TaskEntity deferred = taskMapper.selectById(task.getId());
        if (deferred == null || !"PENDING".equals(deferred.getStatus())) {
            return;
        }
        eventService.publish(deferred.getProjectId(), deferred.getRequirementGroupId(), "task.updated",
                deferred.getId().toString(), TaskEventPayloads.taskUpdated(deferred));
        sendAgentCard(deferred, "task-" + deferred.getId(), "PENDING", null,
                "当前开发现场正被另一项任务使用，将在其完成后自动继续");
    }

    private boolean isWorkspaceWriteLeaseHeld(RuntimeException failure) {
        return failure instanceof ApiException api && "WORKSPACE_WRITE_LEASE_HELD".equals(api.code());
    }

    /**
     * Planner 不进入 TaskRun 图：它只生成 Task 级 PLAN 产物，成功后才由物化服务冻结正式步骤。
     * 外部 Agent 调用发生在数据库事务外，避免持锁调用 LLM 或 Worker。
     */
    private boolean runPlanBootstrap(TaskEntity task, TaskStepEntity planner, TaskExecutionContext ctx) {
        Optional<Agent> agent = agentRegistry.resolve(planner.getAssignedAgentId(), "PLANNER");
        if (agent.isEmpty()) {
            markStepSettled(task, planner, RunOutcome.FAILED);
            finishTaskIfStartable(task, ctx, StateMachineDecision.Action.COMPLETE_FAILED);
            return false;
        }
        while (true) {
            sendPlanningStartedCard(task);
            markStepRunning(task, planner);
            // 规划期心跳：刷新任务 updated_at，防止恢复调度器把长规划任务误判为卡死续跑
            taskMapper.touchUpdatedAt(task.getId());
            AgentInput input = contextAssembler.assemble(task, planner, OrchestrationPhase.PLAN,
                    ctx.feedbackFor(planner.getId()), null,
                    null, null, null, ctx.groupContext);
            AgentRunOutcome outcome = safeExecute(agent.get(), OrchestrationPhase.PLAN, input);
            if (outcome.getPlanResult() != null) {
                ctx.planResult = outcome.getPlanResult();
            }
            StateMachineDecision decision = stateMachine.decide(OrchestrationPhase.PLAN, outcome.getOutcome(),
                    ctx.counters);
            ctx.recordOutcome(planner.getId(), OrchestrationPhase.PLAN, outcome);
            if (decision.getAction() == StateMachineDecision.Action.ADVANCE && outcome.getPlanResult() != null) {
                try {
                    planMaterialization.materialize(task, outcome.getPlanResult());
                } catch (ApiException e) {
                    markStepSettled(task, planner, RunOutcome.FAILED);
                    failTaskIfStartable(task, e.getMessage());
                    return false;
                }
                TaskEntity materialized = taskMapper.selectById(task.getId());
                sendAgentCard(materialized == null ? task : materialized, "task-" + task.getId(),
                        "PENDING", "PLAN", "执行计划已生成", outcome.getPlanResult());
                // 规划完成、步骤已物化：任务保持 PLANNING，由 orchestrate 入口统一原子认领到
                // RUNNING——保证前端在规划期看到 PLANNING，并发编排中仅一个执行器拿到执行权。
                return true;
            }
            if (decision.getAction() == StateMachineDecision.Action.RETRY_PHASE) {
                continue;
            }
            markStepSettled(task, planner, outcome.getOutcome());
            finishTaskIfStartable(task, ctx, decision.getAction());
            return false;
        }
    }

    /**
     * 编排意外中止时把任务落到 FAILED 终态：重查最新状态，仅当任务仍处于
     * PLANNING/PENDING/RUNNING（无终态、无用户取消意图）时覆盖，避免并发取消
     * （CANCELLING/CANCELLED）或已终态的任务被误改；随后走统一终态链路
     * （落库 + task.updated 事件 + TASK_FAILED 通知）并以编排助手身份回群失败卡片。
     */
    private void failStartup(TaskEntity task, RuntimeException cause) {
        log.error("orchestration aborted by unexpected failure, taskId={}", task.getId(), cause);
        TaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null || !STARTABLE_TASK_STATUSES.contains(latest.getStatus())) {
            log.warn("startup failure not persisted, task already left startable states, taskId={} status={}",
                    task.getId(), latest == null ? "MISSING" : latest.getStatus());
            return;
        }
        updateTaskStatus(latest, "FAILED");
        sendAgentCard(latest, "task-" + latest.getId(), "FAILED", null,
                "任务启动失败：执行环境暂不可用，请稍后重试或联系管理员");
    }

    /**
     * 规划完成、任务被原子认领到 RUNNING 后补发 task.updated 事件：裸 SQL
     * {@code claimForOrchestration} 不发布任何事件，若不补发，前端 SSE 将永远看不到
     * PLANNING→RUNNING，直接跳到终态。只发事件不重复 updateById（认领 SQL 已置 RUNNING）。
     */
    private void publishTaskRunningEvent(UUID taskId) {
        TaskEntity latest = taskMapper.selectById(taskId);
        if (latest == null || !"RUNNING".equals(latest.getStatus())) {
            return;
        }
        eventService.publish(latest.getProjectId(), latest.getRequirementGroupId(), "task.updated",
                latest.getId().toString(), TaskEventPayloads.taskUpdated(latest));
    }

    /**
     * 规划失败落 FAILED 前的状态护栏：重查最新任务状态，仅当仍处于可编排启动态（PLANNING/
     * PENDING/RUNNING，无终态、无用户取消意图）时才覆盖，避免用过期内存对象把并发取消
     * （CANCELLING/CANCELLED）或已终态的任务误改为 FAILED。与 failStartup 的覆盖条件一致。
     */
    private void failTaskIfStartable(TaskEntity task, String message) {
        TaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null || !STARTABLE_TASK_STATUSES.contains(latest.getStatus())) {
            log.warn("plan failure not persisted, task already left startable states, taskId={}",
                    task.getId());
            return;
        }
        updateTaskStatus(latest, "FAILED");
        sendAgentCard(latest, "task-" + latest.getId(), "FAILED", null, message);
    }

    /**
     * 规划期到达终态前的状态护栏：重查最新任务状态，仅当仍处于可编排启动态时才允许
     * {@link #finishTask(TaskEntity, TaskExecutionContext, StateMachineDecision.Action)} 覆盖
     * （避免把并发取消的任务误改为 CANCELLED/FAILED）；任务已离开启动态则放弃覆盖，只记日志。
     */
    private void finishTaskIfStartable(TaskEntity task, TaskExecutionContext ctx,
                                       StateMachineDecision.Action action) {
        TaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null || !STARTABLE_TASK_STATUSES.contains(latest.getStatus())) {
            log.warn("plan terminal not persisted, task already left startable states, taskId={}",
                    task.getId());
            return;
        }
        finishTask(latest, ctx, action);
    }

    private List<TaskStepEntity> loadSteps(UUID taskId) {
        return stepMapper.selectList(Wrappers.<TaskStepEntity>lambdaQuery()
                .eq(TaskStepEntity::getTaskId, taskId)
                .orderByAsc(TaskStepEntity::getSequenceNo));
    }

    /**
     * 执行一个 step 节点：重查该 step 最新数据（PLANNER 可能回填过指令）→ 解析 Agent →
     * 建 TaskRun 执行 → 落产物 → 依据状态机决策在图中路由下一步。
     * 返回仅含可序列化基本值的图状态（projectId/taskId/route）。
     */
    private Map<String, Object> runStepNode(TaskStepEntity stepTemplate, TaskOrchestrationState state) {
        TaskExecutionContext ctx = executions.get(state.getTaskId());
        TaskEntity task = ctx.task;
        TaskStepEntity step = stepMapper.selectById(stepTemplate.getId());
        if (step == null) {
            log.warn("STEP_MISSING taskId={} stepId={}", task.getId(), stepTemplate.getId());
            return routeState(state, "next");
        }
        OrchestrationPhase phase = stepPhase(step, ctx.steps);
        Optional<Agent> agent = agentRegistry.resolve(step.getAssignedAgentId(), step.getRole());
        if (agent.isEmpty()) {
            log.warn("NO_AGENT step skipped taskId={} stepId={} role={} agentId={}", task.getId(), step.getId(),
                    step.getRole(), step.getAssignedAgentId());
            markStepSkipped(task, step);
            ctx.clearInfrastructureFeedbackFor(step.getId());
            ctx.retryOf = null;
            return routeState(state, "next");
        }
        markStepRunning(task, step);
        TaskRunEntity run = taskRunService.createForStep(task.getProjectId(), task.getId(), step.getId(),
                step.getRole(), step.getAssignedAgentId(), task.getCreatedBy(), ctx.retryOf);
        taskRunService.markRunning(run.getId());
        AgentInput input = contextAssembler.assemble(task, step, phase, ctx.feedbackFor(step.getId()), run.getId(), ctx.planResult,
                ctx.codingResult, ctx.testResult, ctx.groupContext);
        AgentRunOutcome outcome = safeExecute(agent.get(), phase, input);
        ctx.lastRunId = run.getId();
        if (phase == OrchestrationPhase.CODING && outcome.getOutcome() == RunOutcome.SUCCEEDED) {
            ctx.lastCodingRunId = run.getId();
        }
        if (phase == OrchestrationPhase.CODING && outcome.getCodingResult() != null) {
            ctx.codingResult = outcome.getCodingResult();
        } else if (phase == OrchestrationPhase.TESTING && outcome.getTestResult() != null) {
            ctx.testResult = outcome.getTestResult();
        }
        // AGENTS.md：Run 产物必须先成功落库，再发布 Run 终态事件；产物类型使用稳定相位名，
        // 不泄漏可扩展的 step role，保证前端时间线可识别 CODING/TESTING/REVIEWING。
        artifactService.createRunArtifact(task, run, step, artifactType(phase), runArtifactSummary(step, outcome));
        taskRunService.complete(run.getId(), terminalStatus(outcome.getOutcome()));
        markStepSettled(task, step, outcome.getOutcome());
        StateMachineDecision decision = stateMachine.decide(phase, outcome.getOutcome(), ctx.counters);
        if (decision.getAction() == StateMachineDecision.Action.COMPLETE_SUCCESS
                && hasFollowingStep(step, ctx.steps)) {
            decision = StateMachineDecision.advance(phase);
        }
        ctx.recordOutcome(step.getId(), phase, outcome);
        String route;
        switch (decision.getAction()) {
            case ADVANCE -> {
                ctx.retryOf = null;
                route = "next";
            }
            case REQUEUE_CODING -> {
                ctx.retryOf = ctx.lastRunId;
                route = "requeue";
            }
            case RETRY_PHASE -> {
                ctx.retryOf = ctx.lastRunId;
                route = "retry";
            }
            default -> {
                finishTask(task, ctx, decision.getAction());
                route = GraphDefinition.END;
            }
        }
        return routeState(state, route);
    }

    private boolean hasFollowingStep(TaskStepEntity current, List<TaskStepEntity> steps) {
        int index = indexOfStep(steps, current);
        return index >= 0 && index + 1 < steps.size();
    }

    /**
     * step 角色 → 编排相位；自定义角色按序列位置推断：REVIEWER 步骤之前 → TESTING，
     * 之后/无 REVIEWER → REVIEWING（专项检查的失败语义挂测试或审查环节）。
     */
    private OrchestrationPhase stepPhase(TaskStepEntity step, List<TaskStepEntity> orderedSteps) {
        String role = step.getRole();
        return switch (role == null ? "" : role) {
            case "PLANNER" -> OrchestrationPhase.PLAN;
            case "DEVELOPER" -> OrchestrationPhase.CODING;
            case "TESTER" -> OrchestrationPhase.TESTING;
            case "REVIEWER" -> OrchestrationPhase.REVIEWING;
            default -> inferCustomPhase(step, orderedSteps);
        };
    }

    private OrchestrationPhase inferCustomPhase(TaskStepEntity step, List<TaskStepEntity> orderedSteps) {
        int reviewIndex = indexOfRole(orderedSteps, "REVIEWER");
        if (reviewIndex < 0) {
            return OrchestrationPhase.REVIEWING;
        }
        int stepIndex = indexOfStep(orderedSteps, step);
        return stepIndex >= 0 && stepIndex < reviewIndex ? OrchestrationPhase.TESTING : OrchestrationPhase.REVIEWING;
    }

    private int indexOfRole(List<TaskStepEntity> steps, String role) {
        for (int i = 0; i < steps.size(); i++) {
            if (role.equals(steps.get(i).getRole())) {
                return i;
            }
        }
        return -1;
    }

    private int indexOfStep(List<TaskStepEntity> steps, TaskStepEntity step) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).getId().equals(step.getId())) {
                return i;
            }
        }
        return -1;
    }

    private Map<String, Object> routeState(TaskOrchestrationState state, String route) {
        return Map.of("projectId", state.getProjectId().toString(), "taskId", state.getTaskId().toString(),
                "route", route);
    }

    /**
     * Agent 抛异常统一按基础设施失败处理，避免异常破坏状态机推进；并在相位总时限内等待 `agent.run()`
     * 返回——超时按基础设施失败落库，失败码 AGENT_RUN_TIMEOUT。底层 Worker 请求挂起由其 HTTP
     * 超时（app.worker.response-timeout）兜底，旧线程晚回写由 TaskRun 的 RUNNING 守卫在
     * {{@code complete()}} 处拒绝。
     */
    private AgentRunOutcome safeExecute(Agent agent, OrchestrationPhase phase, AgentInput input) {
        java.time.Duration limit = orchestrationTimeout.timeoutFor(phase);
        java.util.concurrent.Future<AgentRunOutcome> future =
                taskRunTimeoutExecutor.submit(() -> agent.run(input));
        try {
            return future.get(limit.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true); // 尽力中断；阻塞 HTTP 未必被打断，由网络超时兜底
            log.warn("agent run timed out phase={} limit={}ms", phase, limit.toMillis());
            return infrastructureFailure(phase, "agent run timed out after " + limit.toSeconds() + "s",
                    "AGENT_RUN_TIMEOUT");
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            return infrastructureFailure(phase, "agent execution failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return infrastructureFailure(phase, "agent run interrupted", null);
        } catch (RuntimeException e) {
            return infrastructureFailure(phase, "agent execution failed: " + e.getMessage(), null);
        }
    }

    private AgentRunOutcome infrastructureFailure(OrchestrationPhase phase, String message, String failureCode) {
        AgentRunOutcome failure = new AgentRunOutcome();
        failure.setPhase(phase);
        failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
        failure.setMessage(message);
        failure.setFailureCode(failureCode);
        return failure;
    }

    private String terminalStatus(RunOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> "SUCCEEDED";
            case CANCELLED -> "CANCELLED";
            default -> "FAILED";
        };
    }

    private String artifactType(OrchestrationPhase phase) {
        return switch (phase) {
            case CODING -> "CODING";
            case TESTING -> "TESTING";
            case REVIEWING -> "REVIEWING";
            case PLAN -> "PLAN";
        };
    }

    /**
     * Run 级执行产物的脱敏摘要：角色、终态、Agent 反馈消息与每轮 LLM 观测。观测经
     * {@link LlmObservation#toSummary()} 序列化为脱敏 Map（仅 phase/round/字符数/结束原因/
     * 工具名/错误码/sha256），路径与敏感键由服务端 sanitize 兜底。
     */
    private Map<String, Object> runArtifactSummary(TaskStepEntity step, AgentRunOutcome outcome) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("role", step.getRole());
        // 供 ArtifactService 判断是否需要把内部异常归一化为稳定的基础设施错误说明。
        summary.put("outcome", outcome.getOutcome() == null ? null : outcome.getOutcome().name());
        summary.put("status", terminalStatus(outcome.getOutcome()));
        summary.put("message", outcome.getMessage());
        if (outcome.getFailureCode() != null) {
            summary.put("failureCode", outcome.getFailureCode());
        }
        if (outcome.getObservations() != null && !outcome.getObservations().isEmpty()) {
            List<Map<String, Object>> observations = outcome.getObservations().stream()
                    .map(LlmObservation::toSummary)
                    .toList();
            summary.put("observations", observations);
        }
        if ("REVIEWER".equals(step.getRole()) && outcome.getReviewResult() != null) {
            summary.put("review", reviewSummary(outcome.getReviewResult()));
        }
        return summary;
    }

    /**
     * REVIEW 产物的结构化摘要（T4.3 AI_REVIEW check 数据源）：success、摘要、严重度统计与
     * findings（文件/行/问题/建议，均截断脱敏），供交付侧在 PR 创建后映射为质量门禁检查结果。
     */
    private Map<String, Object> reviewSummary(ReviewResult review) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", review.isSuccess());
        result.put("summary", truncate(review.getSummary(), 2000));
        result.put("needsCodingFix", review.isNeedsCodingFix());
        Map<String, Integer> severityCount = new LinkedHashMap<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (ReviewResult.Finding finding : review.getFindings()) {
            String severity = finding.getSeverity() == null ? "UNKNOWN" : finding.getSeverity();
            severityCount.merge(severity, 1, Integer::sum);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("severity", severity);
            if (finding.getFile() != null) {
                item.put("file", finding.getFile());
            }
            if (finding.getLine() != null) {
                item.put("line", finding.getLine());
            }
            item.put("issue", truncate(finding.getIssue(), 1000));
            if (finding.getSuggestion() != null) {
                item.put("suggestion", truncate(finding.getSuggestion(), 1000));
            }
            findings.add(item);
        }
        result.put("severityCount", severityCount);
        result.put("findings", findings);
        if (review.getSuggestions() != null && !review.getSuggestions().isEmpty()) {
            result.put("suggestions", review.getSuggestions().stream()
                    .map(suggestion -> truncate(suggestion, 500)).toList());
        }
        return result;
    }

    private String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private String lastDeveloperNodeId(List<TaskStepEntity> steps) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            if ("DEVELOPER".equals(steps.get(index).getRole())) {
                return steps.get(index).getId().toString();
            }
        }
        return steps.get(0).getId().toString();
    }

    private void markStepRunning(TaskEntity task, TaskStepEntity step) {
        step.setStatus("RUNNING");
        step.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        stepMapper.updateById(step);
        publishStepUpdated(task, step);
        sendAgentCard(task, "step-" + step.getId(), task.getStatus(), step.getRole(),
                roleLabel(step.getRole()) + "步骤开始执行");
    }

    private void markStepSkipped(TaskEntity task, TaskStepEntity step) {
        step.setStatus("SKIPPED");
        step.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        stepMapper.updateById(step);
        publishStepUpdated(task, step);
        sendAgentCard(task, "step-" + step.getId(), task.getStatus(), step.getRole(),
                roleLabel(step.getRole()) + "步骤已跳过");
    }

    private void markStepSettled(TaskEntity task, TaskStepEntity step, RunOutcome outcome) {
        step.setStatus(outcome == RunOutcome.SUCCEEDED ? "SUCCEEDED" : "FAILED");
        step.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        stepMapper.updateById(step);
        publishStepUpdated(task, step);
        sendAgentCard(task, "step-" + step.getId(), step.getStatus(), step.getRole(), stepSettledMessage(step));
    }

    private void publishStepUpdated(TaskEntity task, TaskStepEntity step) {
        eventService.publish(task.getProjectId(), task.getRequirementGroupId(), "task-step.updated",
                step.getId().toString(), TaskEventPayloads.taskStepUpdated(task.getProjectId(), step));
    }

    /**
     * 任务到达终态：成功路径按交付模式分叉——DIFF_FIRST 先创建待确认 Diff 批次再置
     * WAITING_DIFF_CONFIRMATION（失败降级 SUCCEEDED），MR_FIRST 创建 SYSTEM 授权的内部批次后进入
     * DELIVERING（交付模块监听 delivery.started 执行 commit→push，随后等待 MR 前预检）；其余按取消/失败
     * 落终态；随后以编码 Agent 身份把任务结果卡片回群（失败不阻断编排）。
     */
    private void finishTask(TaskEntity task, TaskExecutionContext ctx, StateMachineDecision.Action action) {
        FinishingStatus finishing = switch (action) {
            case COMPLETE_SUCCESS -> completeSuccess(task, ctx);
            case COMPLETE_CANCELLED -> new FinishingStatus("CANCELLED", null, null);
            default -> new FinishingStatus("FAILED", null, null);
        };
        updateTaskStatus(task, finishing.status());
        if (finishing.reviewBatchId() != null) {
            sendDiffCard(task, finishing.reviewBatchId());
        }
        sendAgentCard(task, "task-" + task.getId(), finishing.status(), null, taskResultMessage(finishing));
    }

    /**
     * 成功终态按交付模式路由：MR_FIRST 直达系统交付（仅 commit/push），DIFF_FIRST 走待确认 Diff 批次。
     */
    private FinishingStatus completeSuccess(TaskEntity task, TaskExecutionContext ctx) {
        if (DeliveryMode.MR_FIRST.equals(task.getDeliveryMode())) {
            return completeWithMrFirst(task, ctx);
        }
        return completeWithDiffBatch(task, ctx);
    }

    /**
     * MR_FIRST 成功终态：短事务内创建系统授权 Diff 批次（reviewStatus=ACCEPTED +
     * confirmationSource=SYSTEM，见 {@link FinalDiffBundleService#createSystemAcceptedBatch}），
     * Task 直达 DELIVERING 并发布 delivery.started（SSE + 领域事件双通道）。事务提交后由
     * MR_FIRST 交付执行器（MrFirstDeliveryService，AFTER_COMMIT 监听）或兜底扫描领取租约，
     * 执行逐仓库 commit→push，全部推送后进入 WAITING_PREFLIGHT。Dry Run、独立 CQ+1 与显式
     * 创建 MR 属于后续阶段；预检未完成不能记为代码交付失败。DELIVERING 不是编排终态，
     * 不触发 TASK_COMPLETED 通知。
     */
    private FinishingStatus completeWithMrFirst(TaskEntity task, TaskExecutionContext ctx) {
        log.info("mr-first task enters delivery taskId={} mode={} reason={}", task.getId(), task.getDeliveryMode(),
                task.getDeliveryReason());
        try {
            UUID finalCodingRunId = ctx.lastCodingRunId;
            if (finalCodingRunId == null) {
                finalCodingRunId = taskMapper.selectLastSucceededCodingRunId(task.getId());
            }
            UUID reviewBatchId = finalDiffBundles.createSystemAcceptedBatch(task.getProjectId(), task.getId(),
                    finalCodingRunId);
            // SYSTEM 批次只承载快照、租约和交付审计，不能复用 DIFF_FIRST 的用户确认卡片。
            // MR_FIRST 的用户可见进度由 delivery.started / delivery.repository.updated / MR 卡片提供。
            log.info("mr-first system batch materialized taskId={} reviewBatchId={}", task.getId(), reviewBatchId);
            return new FinishingStatus("DELIVERING", null, null);
        } catch (ApiException e) {
            if ("FINAL_DIFF_EMPTY".equals(e.code())) {
                log.warn("mr-first final diff empty, task finishes SUCCEEDED, taskId={}: {}", task.getId(),
                        e.getMessage());
                publishDiffReviewSkipped(task, e.code());
                return new FinishingStatus("SUCCEEDED", null, NO_CODE_CHANGES_MESSAGE);
            }
            log.warn("mr-first batch creation failed, task finishes FAILED, taskId={}, code={}: {}",
                    task.getId(), e.code(), e.getMessage());
            return new FinishingStatus("FAILED", null, null);
        } catch (RuntimeException e) {
            log.error("mr-first batch creation failed, task finishes FAILED, taskId={}: {}",
                    task.getId(), e.getMessage(), e);
            return new FinishingStatus("FAILED", null, null);
        }
    }

    /**
     * 成功终态：生成待用户确认的 Diff 批次。无未提交改动（FINAL_DIFF_EMPTY）视为业务上的成功
     * 降级为 SUCCEEDED 并发布 diff-review.skipped 事件作为依据；其余失败（内部一致性、快照无效、
     * Worker 不可用等）落 FAILED，不伪装成成功（后端3 决策：按异常类型区分，不统一降级）。
     */
    private FinishingStatus completeWithDiffBatch(TaskEntity task, TaskExecutionContext ctx) {
        try {
            UUID finalCodingRunId = ctx.lastCodingRunId;
            if (finalCodingRunId == null) {
                // 续跑（如从 REVIEWER 恢复）本次可能没有成功的 CODING run：回退到任务最近一次
                // SUCCEEDED 的 DEVELOPER 运行，作为最终 Diff 批次的来源；仍无则走 FINAL_CODING_RUN_INVALID。
                finalCodingRunId = taskMapper.selectLastSucceededCodingRunId(task.getId());
                if (finalCodingRunId != null) {
                    ctx.lastCodingRunId = finalCodingRunId;
                }
            }
            UUID reviewBatchId = finalDiffBundles.createPendingBatch(task.getProjectId(), task.getId(), finalCodingRunId);
            return new FinishingStatus(WAITING_DIFF_CONFIRMATION, reviewBatchId, null);
        } catch (ApiException e) {
            if ("FINAL_DIFF_EMPTY".equals(e.code())) {
                log.warn("final diff empty, task finishes SUCCEEDED, taskId={}: {}", task.getId(), e.getMessage());
                publishDiffReviewSkipped(task, e.code());
                return new FinishingStatus("SUCCEEDED", null, NO_CODE_CHANGES_MESSAGE);
            }
            log.warn("final diff batch creation failed, task finishes FAILED, taskId={}, code={}: {}",
                    task.getId(), e.code(), e.getMessage());
            return new FinishingStatus("FAILED", null, null);
        } catch (RuntimeException e) {
            log.error("final diff batch creation failed, task finishes FAILED, taskId={}: {}",
                    task.getId(), e.getMessage(), e);
            return new FinishingStatus("FAILED", null, null);
        }
    }

    /**
     * 无未提交改动导致 Diff 审核跳过时发布的事件，作为 SUCCEEDED 降级的事件依据（脱敏、带项目归属）。
     */
    private void publishDiffReviewSkipped(TaskEntity task, String reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("projectId", task.getProjectId());
        payload.put("taskId", task.getId());
        payload.put("reason", reason);
        eventService.publish(task.getProjectId(), task.getRequirementGroupId(), "diff-review.skipped",
                task.getId().toString(), payload);
    }

    /**
     * 以团队编排助手（ORCHESTRATOR Agent）身份把 TASK_STATUS 卡片回群：任务进度、Diff
     * 确认与终态卡片使用统一发送者，不再依赖各步骤恰好分配到 Agent 实体；团队查不到
     * 编排助手时降级为 SYSTEM 系统消息（sendAsSystem），保证卡片永不因缺少发送者而丢失。
     * 发送失败记日志跳过，回群失败不等于任务失败。
     */
    private void sendPlanningStartedCard(TaskEntity task) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("taskId", task.getId().toString());
        content.put("status", "PLANNING");
        content.put("phase", "PLAN");
        if (task.getDeliveryMode() != null) content.put("deliveryMode", task.getDeliveryMode());
        if (task.getDeliveryReason() != null) content.put("deliveryReason", task.getDeliveryReason());
        content.put("message", "正在制定执行计划");
        content.put("currentStepId", null);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("summary", null);
        plan.put("steps", List.of());
        content.put("plan", plan);
        publishTaskStatusCard(task, content, "planning");
    }

    private void sendAgentCard(TaskEntity task, String idSuffix, String status, String node, String message) {
        sendAgentCard(task, idSuffix, status, node, message, null);
    }

    private void sendAgentCard(TaskEntity task, String idSuffix, String status, String node, String message,
                               PlanResult planResult) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("taskId", task.getId().toString());
        content.put("status", status);
        content.put("phase", cardPhase(status, node));
        if (task.getDeliveryMode() != null) {
            content.put("deliveryMode", task.getDeliveryMode());
        }
        if (task.getDeliveryReason() != null) {
            content.put("deliveryReason", task.getDeliveryReason());
        }
        if (node != null) {
            content.put("node", node);
        }
        if (message != null) {
            content.put("message", message);
        }
        UUID currentStepId = currentRunningStepId(task.getId());
        content.put("currentStepId", currentStepId == null ? null : currentStepId.toString());
        content.put("plan", taskPlanContent(task, planResult, idSuffix, message));
        publishTaskStatusCard(task, content, idSuffix);
    }

    private void publishTaskStatusCard(TaskEntity task, Map<String, Object> content, String idSuffix) {
        MessageSendRequest body = new MessageSendRequest();
        body.setType("TASK_STATUS");
        body.setClientMessageId("task-card-" + task.getId());
        body.setContent(content);
        UUID cardSenderId = orchestratorAgents.resolveIdForTask(task);
        try {
            if (cardSenderId != null) {
                messageService.upsertTaskStatusCard(task.getRequirementGroupId(), cardSenderId, body);
            } else {
                log.warn("orchestrator agent missing, card degrades to SYSTEM, taskId={}, suffix={}",
                        task.getId(), idSuffix);
                messageService.upsertTaskStatusCard(task.getRequirementGroupId(), null, body);
            }
        } catch (RuntimeException e) {
            log.warn("agent card skipped, taskId={}, suffix={}: {}", task.getId(), idSuffix, e.getMessage());
        }
    }

    private String cardPhase(String status, String node) {
        if (node != null) {
            return switch (node) {
                case "PLANNER" -> "PLAN";
                case "DEVELOPER" -> "CODING";
                case "TESTER" -> "TESTING";
                case "REVIEWER" -> "REVIEWING";
                default -> "RUNNING";
            };
        }
        return Set.of("PLANNING", "PENDING").contains(status) ? "PLAN" : "DELIVERY";
    }

    private UUID currentRunningStepId(UUID taskId) {
        List<TaskStepEntity> steps = stepMapper.selectList(Wrappers.<TaskStepEntity>lambdaQuery()
                .eq(TaskStepEntity::getTaskId, taskId).orderByAsc(TaskStepEntity::getSequenceNo));
        if (steps == null) return null;
        return steps.stream().filter(step -> "RUNNING".equals(step.getStatus()))
                .map(TaskStepEntity::getId).findFirst().orElse(null);
    }

    private Map<String, Object> taskPlanContent(TaskEntity task, PlanResult planResult, String idSuffix,
                                                String message) {
        List<TaskStepEntity> steps = stepMapper.selectList(Wrappers.<TaskStepEntity>lambdaQuery()
                .eq(TaskStepEntity::getTaskId, task.getId()).orderByAsc(TaskStepEntity::getSequenceNo));
        if (steps == null) steps = List.of();
        String summary = planResult == null ? null : planResult.getTaskUnderstanding();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("summary", summary);
        plan.put("steps", steps.stream().map(step -> cardStep(step, idSuffix, message)).toList());
        return plan;
    }

    private Map<String, Object> cardStep(TaskStepEntity step, String idSuffix, String message) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("stepId", step.getId().toString());
        value.put("sequence", step.getSequenceNo());
        value.put("title", step.getTitle());
        value.put("role", step.getRole());
        value.put("status", step.getStatus());
        String stepPrefix = "step-" + step.getId();
        if (stepPrefix.equals(idSuffix) && message != null) {
            value.put("message", message);
        } else {
            value.put("message", stepMessage(step));
        }
        return value;
    }

    private String stepMessage(TaskStepEntity step) {
        return switch (step.getStatus()) {
            case "RUNNING" -> roleLabel(step.getRole()) + "步骤正在执行";
            case "SUCCEEDED" -> roleLabel(step.getRole()) + "步骤已完成";
            case "FAILED" -> roleLabel(step.getRole()) + "步骤失败";
            case "SKIPPED" -> roleLabel(step.getRole()) + "步骤已跳过";
            case "CANCELLED" -> roleLabel(step.getRole()) + "步骤已取消";
            default -> null;
        };
    }

    /**
     * 正式 Diff 批次生成后以编排助手身份回一条 DIFF 卡（content.diffId 指向批次内首个 Diff）：
     * 用户引用该卡即可被 TaskTriggerService 识别为续作，复用源 Workspace 发起增量修改。
     * DIFF 卡的 content 形状满足消息契约（diffId 必填，title/additions/deletions 可选）。
     * 缺编排助手时降级为 senderType=SYSTEM 的 DIFF 卡，仍保留 type=DIFF 与 content.diffId，
     * 以保证前端渲染和引用续作不因团队 Agent 配置缺失而失效；回卡失败不影响任务结果。
     */
    private void sendDiffCard(TaskEntity task, UUID reviewBatchId) {
        List<DiffEntity> values = diffMapper.selectList(Wrappers.<DiffEntity>lambdaQuery()
                .eq(DiffEntity::getReviewBatchId, reviewBatchId)
                .orderByAsc(DiffEntity::getProjectRepositoryId));
        if (values.isEmpty()) {
            log.warn("diff card skipped, no diff in review batch, taskId={}, reviewBatchId={}",
                    task.getId(), reviewBatchId);
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("diffId", values.get(0).getId().toString());
        content.put("taskId", task.getId().toString());
        content.put("reviewBatchId", reviewBatchId.toString());
        content.put("title", task.getTitle());
        content.put("additions", aggregateStat(values, "additions"));
        content.put("deletions", aggregateStat(values, "deletions"));
        content.put("reviewStatus", "PENDING_CONFIRMATION");
        content.put("deliveryStatus", "NOT_STARTED");
        MessageSendRequest body = new MessageSendRequest();
        body.setType("DIFF");
        body.setClientMessageId("diff-card-" + task.getId());
        body.setContent(content);
        UUID cardSenderId = orchestratorAgents.resolveIdForTask(task);
        try {
            if (cardSenderId != null) {
                messageService.upsertDiffCard(task.getRequirementGroupId(), cardSenderId, body);
            } else {
                log.warn("orchestrator agent missing, diff card degrades to SYSTEM, taskId={}", task.getId());
                messageService.upsertDiffCard(task.getRequirementGroupId(), null, body);
            }
        } catch (RuntimeException e) {
            log.warn("diff card skipped, taskId={}, reviewBatchId={}: {}", task.getId(), reviewBatchId, e.getMessage());
        }
    }

    /**
     * 聚合批次内所有 Diff 的某类变更统计（总 Diff 卡按全仓求和）。
     */
    private int aggregateStat(List<DiffEntity> values, String key) {
        return values.stream()
                .map(DiffEntity::getChangeStats)
                .filter(Objects::nonNull)
                .mapToInt(stats -> stats.get(key) instanceof Number n ? n.intValue() : 0)
                .sum();
    }

    /**
     * step 级卡片文案：以中文表达该角色步骤的完成/失败。
     */
    private String stepSettledMessage(TaskStepEntity step) {
        return switch (step.getStatus()) {
            case "SUCCEEDED" -> roleLabel(step.getRole()) + "步骤已完成";
            default -> roleLabel(step.getRole()) + "步骤失败，已按重试或修复策略处理";
        };
    }

    /**
     * 任务结果卡片文案：按终态表达交付确认、完成、失败或取消。
     */
    private String taskResultMessage(FinishingStatus finishing) {
        if (finishing.message() != null) {
            return finishing.message();
        }
        return switch (finishing.status()) {
            case WAITING_DIFF_CONFIRMATION -> "任务开发完成，等待你对 Diff 的确认";
            case "DELIVERING" -> "任务开发完成，正在提交并推送代码";
            case "WAITING_PREFLIGHT" -> "代码已推送，等待 MR 前预检";
            case "SUCCEEDED" -> "任务已完成";
            case "FAILED" -> "任务执行失败";
            case "CANCELLED" -> "任务已取消";
            default -> "任务状态更新：" + finishing.status();
        };
    }

    /**
     * 角色英文 → 中文业务名，用于卡片文案；未识别角色原样返回。
     */
    private String roleLabel(String role) {
        return switch (role) {
            case "PLANNER" -> "计划";
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

    /**
     * 终态判定结果：终态状态码、仅 WAITING_DIFF_CONFIRMATION 时的 Diff 批次号，以及只用于本次
     * TASK_STATUS 卡片的结果文案。文案不持久化，避免无代码变更结果泄漏到其他异步任务。
     */
    private record FinishingStatus(String status, UUID reviewBatchId, String message) {
    }

    /**
     * 一次 orchestrate 会话内的执行现场：跨节点传递的富结果、循环反馈、最近 TaskRun、
     * 有序步骤与循环计数。与图状态解耦，仅在进程内按 taskId 暂存，invoke 结束后由 orchestrate 清理。
     */
    private static final class TaskExecutionContext {
        private final TaskEntity task;
        private final OrchestrationCounters counters = new OrchestrationCounters();
        private List<TaskStepEntity> steps;
        /** 质量反馈只定向给原失败 step 与被 requeue 的 Coding step。 */
        private QualityFeedback qualityFeedback;
        /**
         * 各相位最近一次基础设施失败，仅在该相位重试时优先回灌；不覆盖仍待复核的质量反馈。
         */
        private final java.util.Map<UUID, AgentRunOutcome> infraFeedback = new java.util.HashMap<>();
        private UUID lastRunId;
        private UUID retryOf;
        /**
         * 最后一次 SUCCEEDED 的 CODING run，终态时供 FinalDiffBundleService 生成待确认 Diff 批次。
         */
        private UUID lastCodingRunId;
        private PlanResult planResult;
        private CodingResult codingResult;
        private TestResult testResult;
        /**
         * 本次 orchestrate 快照的群聊/Skill/Memory 上下文，跨节点复用；组装失败时为 null（不阻断）。
         */
        private GroupContext groupContext;

        private TaskExecutionContext(TaskEntity task) {
            this.task = task;
        }

        private AgentRunOutcome feedbackFor(UUID stepId) {
            AgentRunOutcome infrastructure = infraFeedback.get(stepId);
            if (infrastructure != null) {
                return infrastructure;
            }
            if (qualityFeedback != null && (qualityFeedback.sourceStepId().equals(stepId)
                    || qualityFeedback.repairCodingStepId().equals(stepId))) {
                return qualityFeedback.outcome();
            }
            return null;
        }

        private void recordOutcome(UUID stepId, OrchestrationPhase phase, AgentRunOutcome outcome) {
            if (outcome.getOutcome() == RunOutcome.FAILED_INFRASTRUCTURE) {
                infraFeedback.put(stepId, outcome);
                return;
            }
            infraFeedback.remove(stepId);
            if (outcome.getOutcome() == RunOutcome.FAILED_QUALITY
                    && (phase == OrchestrationPhase.TESTING || phase == OrchestrationPhase.REVIEWING)) {
                UUID repairStepId = repairCodingStepId();
                if (repairStepId != null) {
                    qualityFeedback = new QualityFeedback(stepId, repairStepId, outcome);
                }
                return;
            }
            if (outcome.getOutcome() == RunOutcome.SUCCEEDED && qualityFeedback != null
                    && qualityFeedback.sourceStepId().equals(stepId)) {
                qualityFeedback = null;
            }
        }

        private void clearInfrastructureFeedbackFor(UUID stepId) {
            infraFeedback.remove(stepId);
        }

        private UUID repairCodingStepId() {
            if (steps == null) {
                return null;
            }
            UUID codingStepId = null;
            for (TaskStepEntity step : steps) {
                if ("DEVELOPER".equalsIgnoreCase(step.getRole())) {
                    codingStepId = step.getId();
                }
            }
            return codingStepId;
        }

        private record QualityFeedback(UUID sourceStepId, UUID repairCodingStepId, AgentRunOutcome outcome) {
        }
    }
}
