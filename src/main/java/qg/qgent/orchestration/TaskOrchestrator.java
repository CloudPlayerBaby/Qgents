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
import qg.qgent.orchestration.worker.WorkerExecutionTraceContext;
import qg.qgent.orchestration.worker.WorkerToolExecution;
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
    private final TaskRunFailureDiagnosticService failureDiagnostics;
    private final FinalDiffBundleService finalDiffBundles;
    private final DiffMapper diffMapper;
    private final MessageService messageService;
    private final OrchestratorAgentService orchestratorAgents;
    private final TaskPlanMaterializationService planMaterialization;
    private final java.util.concurrent.ExecutorService taskRunTimeoutExecutor;
    private final OrchestrationTimeoutProperties orchestrationTimeout;
    /** TASK_STATUS 卡片的真实 Workspace/Repository 映射；通知失败不得影响编排。 */
    private TaskStatusRepositoryContextService repositoryContextService;

    /**
     * 各编排任务的执行现场（富结果/反馈/计数，不进图状态），按 taskId 暂存。
     */
    private final Map<UUID, TaskExecutionContext> executions = new ConcurrentHashMap<>();

    public TaskOrchestrator(OrchestrationStateMachine stateMachine, WorkflowGraphBuilder workflowGraphBuilder,
                            AgentRegistry agentRegistry, AgentContextAssembler contextAssembler,
                            TaskRunService taskRunService, TaskMapper taskMapper, TaskStepMapper stepMapper,
                            EventService eventService,
                            NotificationService notificationService, SandboxSessionManager sandboxSessionManager,
                            TaskExecutionArtifactService artifactService, TaskRunFailureDiagnosticService failureDiagnostics,
                            FinalDiffBundleService finalDiffBundles,
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
        this.failureDiagnostics = failureDiagnostics;
        this.finalDiffBundles = finalDiffBundles;
        this.diffMapper = diffMapper;
        this.messageService = messageService;
        this.orchestratorAgents = orchestratorAgents;
        this.planMaterialization = planMaterialization;
        this.taskRunTimeoutExecutor = taskRunTimeoutExecutor;
        this.orchestrationTimeout = orchestrationTimeout;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRepositoryContextService(TaskStatusRepositoryContextService repositoryContextService) {
        this.repositoryContextService = repositoryContextService;
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
            int claimed;
            if (startStepId == null) {
                claimed = taskMapper.claimForOrchestration(projectId, taskId);
            } else if (retryOfTaskRunId != null) {
                claimed = taskMapper.claimForRetry(projectId, taskId, retryOfTaskRunId);
            } else {
                claimed = taskMapper.claimForResume(projectId, taskId);
            }
            if (claimed != 1) {
                throw new IllegalStateException("Task " + taskId + " already claimed or not startable (status="
                        + task.getStatus() + ")");
            }
            // 失败补偿：任务从 FAILED 恢复编排（用户重试 / 恢复器续跑）后，撤销之前写入的
            // TASK_FAILED 通知——任务已恢复继续执行，铃铛不应再显示「任务失败」。
            if (startStepId != null && "FAILED".equals(task.getStatus())) {
                notificationService.clearTaskFailedNotifications(taskId.toString());
            }
        }
        TaskExecutionContext ctx = new TaskExecutionContext(task);
        // 续跑来源：首个 TaskRun 的 retryOfTaskRunId 指向被重试的失败运行
        ctx.retryOf = retryOfTaskRunId;
        ctx.startStepId = startStepId;
        // 用户点击重试会进入新的编排会话；从最近一次 Coding 产物恢复同一 TaskStep 的
        // patch 失败计数，避免每个 TaskRun 都把三次失败门槛重新清零。
        if (startStepId != null) {
            ctx.inheritPatchFailureCounts(artifactService.latestPatchFailureCounts(startStepId));
        }
        // 进程内防重入必须保持在 sandbox acquire 之前：只有赢家到达 acquire，避免并发编排器
        // 双持同一 workspace session、输家 finally release 销毁赢家正在用的沙箱。
        TaskExecutionContext previous = executions.putIfAbsent(taskId, ctx);
        if (previous != null) {
            // 恢复器只会在任务长期无活跃 Run 时续跑；若本进程仍保留旧上下文且尚未创建 Run，
            // 说明旧线程卡在 Sandbox 初始化等启动窗口。不能只把异常交给异步监听器吞掉，
            // 否则任务会永久 RUNNING；已有 Run 时保留原执行者继续收敛，避免误判并发执行。
            if (previous.activeRunId == null) {
                failStartup(task, previous, new IllegalStateException(
                        "Task " + taskId + " is already being orchestrated in this process"));
            }
            return;
        }
        try {
            // 先物化 Planner 步骤再获取 Sandbox。这样即使 Sandbox/Worker 在规划调用前失败，
            // failStartup 也能关联一个真实的 taskStepId，创建可查询的失败 Planner Run。
            if (task.getPlanMaterializedAt() == null) {
                planMaterialization.ensurePlannerStep(task);
            }
            sandboxSessionManager.acquire(task.getId(), task.getProjectId(), task.getWorkspaceId());
            // 恢复器可能已将同一启动上下文收敛为 FAILED；旧初始化线程即使晚返回，也不得
            // 再进入 Planner/正式图或创建 Sandbox 后继续修改 Workspace。
            if (ctx.aborted) {
                log.info("orchestration startup context aborted before graph taskId={}", taskId);
                return;
            }
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
            failStartup(task, ctx, e);
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
            TaskRunEntity run = taskRunService.createForStep(task.getProjectId(), task.getId(), planner.getId(),
                    planner.getRole(), planner.getAssignedAgentId(), task.getCreatedBy(), ctx.retryOf);
            taskRunService.markRunning(run.getId());
            AgentRunOutcome missingAgent = infrastructureFailure(OrchestrationPhase.PLAN,
                    "Planner Agent 不可用", "AGENT_NOT_FOUND", "AGENT_RESOLUTION", null);
            recordFailureDiagnostic(task, run, planner, missingAgent);
            artifactService.createRunArtifact(task, run, planner, "PLAN", runArtifactSummary(planner, missingAgent));
            taskRunService.complete(run.getId(), "FAILED", "AGENT_NOT_FOUND", missingAgent.getMessage());
            markStepSettled(task, planner, RunOutcome.FAILED);
            finishTaskIfStartable(task, ctx, StateMachineDecision.Action.COMPLETE_FAILED);
            return false;
        }
        while (true) {
            sendPlanningStartedCard(task);
            ctx.activeStepId = planner.getId();
            ctx.activeRunId = null;
            markStepRunning(task, planner);
            TaskRunEntity run = taskRunService.createForStep(task.getProjectId(), task.getId(), planner.getId(),
                    planner.getRole(), planner.getAssignedAgentId(), task.getCreatedBy(), ctx.retryOf);
            ctx.activeRunId = run.getId();
            ctx.lastRunId = run.getId();
            taskRunService.markRunning(run.getId());
            // 规划期心跳：刷新任务 updated_at，防止恢复调度器把长规划任务误判为卡死续跑
            taskMapper.touchUpdatedAt(task.getId());
            AgentInput input = contextAssembler.assemble(task, planner, OrchestrationPhase.PLAN,
                    ctx.feedbackFor(planner.getId()), null,
                    null, null, null, ctx.groupContext);
            AgentRunOutcome outcome = safeExecute(agent.get(), OrchestrationPhase.PLAN, input);
            taskRunService.appendAgentObservations(run, outcome.getObservations());
            if (outcome.getPlanResult() != null) {
                ctx.planResult = outcome.getPlanResult();
            }
            StateMachineDecision decision = stateMachine.decide(OrchestrationPhase.PLAN, outcome.getOutcome(),
                    outcome.getFailureCode(), ctx.counters);
            ctx.recordOutcome(planner.getId(), OrchestrationPhase.PLAN, outcome);
            if (decision.getAction() == StateMachineDecision.Action.ADVANCE && outcome.getPlanResult() != null) {
                try {
                    planMaterialization.materialize(task, outcome.getPlanResult());
                } catch (ApiException e) {
                    AgentRunOutcome materializationFailure = infrastructureFailure(OrchestrationPhase.PLAN,
                            "plan materialization failed", stableFailureCode(e), "PLAN_MATERIALIZATION", e);
                    recordFailureDiagnostic(task, run, planner, materializationFailure);
                    artifactService.createRunArtifact(task, run, planner, "PLAN", runArtifactSummary(planner, materializationFailure));
                    taskRunService.complete(run.getId(), "FAILED", stableFailureCode(e), e.getMessage());
                    markStepSettled(task, planner, RunOutcome.FAILED);
                    failTaskIfStartable(task, e);
                    return false;
                }
                artifactService.createRunArtifact(task, run, planner, "PLAN", runArtifactSummary(planner, outcome));
                taskRunService.complete(run.getId(), "SUCCEEDED");
                TaskEntity materialized = taskMapper.selectById(task.getId());
                sendAgentCard(materialized == null ? task : materialized, "task-" + task.getId(),
                        "PENDING", "PLAN", "执行计划已生成", outcome.getPlanResult());
                // 规划完成、步骤已物化：任务保持 PLANNING，由 orchestrate 入口统一原子认领到
                // RUNNING——保证前端在规划期看到 PLANNING，并发编排中仅一个执行器拿到执行权。
                return true;
            }
            if (decision.getAction() == StateMachineDecision.Action.RETRY_PHASE) {
                recordFailureDiagnostic(task, run, planner, outcome);
                artifactService.createRunArtifact(task, run, planner, "PLAN", runArtifactSummary(planner, outcome));
                taskRunService.complete(run.getId(), "FAILED", stableFailureCode(outcome), outcome.getMessage());
                markStepSettled(task, planner, outcome.getOutcome());
                continue;
            }
            recordFailureDiagnostic(task, run, planner, outcome);
            artifactService.createRunArtifact(task, run, planner, "PLAN", runArtifactSummary(planner, outcome));
            taskRunService.complete(run.getId(), terminalStatus(outcome.getOutcome()), outcome.getFailureCode(),
                    outcome.getMessage());
            markStepSettled(task, planner, outcome.getOutcome());
            finishTaskIfStartable(task, ctx, decision.getAction());
            return false;
        }
    }

    private String stableFailureCode(RuntimeException failure) {
        return ExecutionContentSanitizer.stableInfrastructureCode(
                failure instanceof ApiException api ? api.code() : null);
    }

    private String stableFailureCode(AgentRunOutcome outcome) {
        return outcome == null ? null : outcome.getFailureCode();
    }

    /**
     * 编排意外中止时把任务落到 FAILED 终态：重查最新状态，仅当任务仍处于
     * PLANNING/PENDING/RUNNING（无终态、无用户取消意图）时覆盖，避免并发取消
     * （CANCELLING/CANCELLED）或已终态的任务被误改；随后走统一终态链路
     * （落库 + task.updated 事件 + TASK_FAILED 通知）并以编排助手身份回群失败卡片。
     */
    private void failStartup(TaskEntity task, TaskExecutionContext ctx, RuntimeException cause) {
        if (ctx != null) {
            ctx.aborted = true;
        }
        log.error("orchestration aborted by unexpected failure, taskId={} exceptionType={} detail={}", task.getId(),
                cause.getClass().getSimpleName(), ExecutionContentSanitizer.sanitizeDiagnosticDetail(cause.getMessage()));
        TaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null || !STARTABLE_TASK_STATUSES.contains(latest.getStatus())) {
            log.warn("startup failure not persisted, task already left startable states, taskId={} status={}",
                    task.getId(), latest == null ? "MISSING" : latest.getStatus());
            return;
        }
        StartupFailure failure = startupFailure(cause);
        AgentRunOutcome startupOutcome = infrastructureFailure(OrchestrationPhase.PLAN, failure.reason(), failure.code(),
                "ORCHESTRATOR_STARTUP", cause);
        // Step 在 TaskRun 创建前已被置为 RUNNING。此处必须跟踪本次节点，不能用前一条已成功的
        // lastRunId 代替，否则 createForStep/markRunning 失败会遗留 RUNNING Step。
        TaskStepEntity activeStep = ctx == null || ctx.activeStepId == null
                ? null : stepMapper.selectById(ctx.activeStepId);
        TaskRunEntity activeRun = ctx == null || ctx.activeRunId == null
                ? null : taskRunService.findById(ctx.activeRunId);
        if (activeRun != null) {
            settleUnexpectedFailureRun(task, activeRun, activeStep, startupOutcome, failure);
        } else if (activeStep != null) {
            createStartupFailureRun(task, ctx, activeStep, startupOutcome, failure);
        } else if (ctx != null && ctx.lastRunId != null) {
            TaskRunEntity run = taskRunService.findById(ctx.lastRunId);
            if (run != null && ("QUEUED".equals(run.getStatus()) || "RUNNING".equals(run.getStatus()))) {
                settleUnexpectedFailureRun(task, run, stepMapper.selectById(run.getTaskStepId()), startupOutcome, failure);
            }
        } else {
            // 续跑/重试时启动阶段失败（如 Sandbox 获取失败）发生在任何 step 节点执行之前，此时
            // 还没有 lastRunId。失败 run 必须关联到本次续跑的起始步骤（startStepId），否则会把
            // 用户重试 TESTER 的失败错误地记成 PLANNER run，导致前端时间线 role 错乱、且重试
            // 目标被引导到错误的步骤。仅在无 startStepId 时才回退到 PLANNER 步骤。
            TaskStepEntity step = (ctx != null && ctx.startStepId != null)
                    ? stepMapper.selectById(ctx.startStepId)
                    : null;
            if (step == null) {
                List<TaskStepEntity> plannerSteps = stepMapper.selectList(Wrappers.<TaskStepEntity>lambdaQuery()
                        .eq(TaskStepEntity::getTaskId, task.getId())
                        .eq(TaskStepEntity::getRole, "PLANNER")
                        .orderByAsc(TaskStepEntity::getSequenceNo)
                        .last("LIMIT 1"));
                step = plannerSteps == null || plannerSteps.isEmpty() ? null : plannerSteps.get(0);
            }
            if (step != null) createStartupFailureRun(task, ctx, step, startupOutcome, failure);
        }
        markActiveStepFailed(task, activeStep);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String publicFailureCode = clientFailureCode(failure.code());
        latest.setFailureCode(publicFailureCode);
        // startupFailure 仅从稳定码或结构化 branch details 构造 reason，不包含上游异常原文。
        latest.setFailureReason(failure.reason());
        latest.setFailureRetryable(failure.retryable());
        latest.setFailureOccurredAt(now);
        updateTaskStatus(latest, "FAILED");
        sendAgentCard(latest, "task-" + latest.getId(), "FAILED", null,
                "任务启动失败：" + failure.title() + "。" + failure.reason()
                        + (failure.retryable() ? "，可以稍后重试" : "，请先修复配置后重试"));
    }

    /** 为节点启动异常补写失败 Run；补写自身失败时仍必须继续收敛 Task 与 Step。 */
    private void createStartupFailureRun(TaskEntity task, TaskExecutionContext ctx, TaskStepEntity step,
                                         AgentRunOutcome outcome, StartupFailure failure) {
        try {
            TaskRunEntity run = taskRunService.createForStep(task.getProjectId(), task.getId(), step.getId(),
                    step.getRole(), step.getAssignedAgentId(), task.getCreatedBy(), ctx == null ? null : ctx.retryOf);
            if (ctx != null) {
                ctx.activeRunId = run.getId();
                ctx.lastRunId = run.getId();
            }
            taskRunService.markRunning(run.getId());
            settleUnexpectedFailureRun(task, run, step, outcome, failure);
        } catch (RuntimeException diagnosticFailure) {
            log.warn("startup failure run could not be persisted taskId={} stepId={} exceptionType={}", task.getId(),
                    step.getId(), diagnosticFailure.getClass().getSimpleName());
        }
    }

    /** 将已创建但尚未完成的当前 Run 收敛为 FAILED，覆盖 QUEUED 与 RUNNING 两种异常窗口。 */
    private void settleUnexpectedFailureRun(TaskEntity task, TaskRunEntity run, TaskStepEntity step,
                                            AgentRunOutcome outcome, StartupFailure failure) {
        try {
            if (step != null) recordFailureDiagnostic(task, run, step, outcome);
            taskRunService.failIfActive(run.getId(), failure.code());
        } catch (RuntimeException settlementFailure) {
            log.warn("startup failure run settlement skipped taskId={} runId={} exceptionType={}", task.getId(),
                    run.getId(), settlementFailure.getClass().getSimpleName());
        }
    }

    /** 当前 Step 已写入 RUNNING 时，任务失败必须同步收敛该 Step，避免前端继续展示执行中。 */
    private void markActiveStepFailed(TaskEntity task, TaskStepEntity step) {
        if (step != null && ("RUNNING".equals(step.getStatus()) || "PENDING".equals(step.getStatus()))) {
            markStepSettled(task, step, RunOutcome.FAILED);
        }
    }

    /**
     * 将内部启动异常收敛为稳定码和用户安全文案。原始异常只进入服务端日志，
     * 不把堆栈、Worker 地址、命令参数或凭据写入 Task/API/SSE。
     */
    private StartupFailure startupFailure(RuntimeException cause) {
        String rawCode = cause instanceof ApiException api ? api.code() : null;
        String code = ExecutionContentSanitizer.stableInfrastructureCode(rawCode);
        String reason;
        boolean retryable = true;
        if ("GIT_BRANCH_NOT_FOUND".equals(code)) {
            // 基线分支不存在是用户可修复的确定性错误：保留仓库与分支名，供卡片与 statusReason
            // 展示「修改基线分支后重试」；从结构化 details 提取，不回显异常原文。
            BranchContext context = branchContext(cause);
            reason = context == null
                    ? ExecutionContentSanitizer.infrastructureDescription(code)
                    : "仓库 " + context.repository() + " 不存在基线分支 " + context.branch()
                    + "，请在项目仓库配置中选择真实存在的分支后重试";
            retryable = true;
        } else {
            reason = ExecutionContentSanitizer.infrastructureDescription(code);
        }
        String title = switch (code) {
            case "GIT_STORE_FETCH_FAILED", "GIT_STORE_SYNC_INVALID", "GIT_REMOTE_SHA_MISMATCH" -> "代码仓库同步失败";
            case "GIT_BASE_REF_NOT_FOUND", "GIT_REF_NOT_FOUND" -> "代码仓库基线不可用";
            case "GIT_BRANCH_NOT_FOUND" -> "基线分支不存在";
            case "SANDBOX_WORKER_UNAVAILABLE", "SANDBOX_WORKER_ERROR" -> "执行环境不可用";
            case "GITHUB_API_UNAVAILABLE" -> "GitHub 服务不可用";
            default -> "任务启动失败";
        };
        return new StartupFailure(code, title, reason, retryable);
    }

    /**
     * 从启动异常的结构化 details 提取仓库与基线分支（仅 GIT_BRANCH_NOT_FOUND 使用）。
     * details 缺失或结构不符时返回 null，调用方回退到脱敏通用文案。
     */
    private BranchContext branchContext(RuntimeException cause) {
        if (!(cause instanceof ApiException api)) {
            return null;
        }
        try {
            for (Object detail : api.details()) {
                if (detail instanceof Map<?, ?> map) {
                    Object fullName = map.get("fullName");
                    Object branch = map.get("branch");
                    if (fullName instanceof String f && branch instanceof String b
                            && !f.isBlank() && !b.isBlank()) {
                        return new BranchContext(f, b);
                    }
                }
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private record BranchContext(String repository, String branch) {
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
    private void failTaskIfStartable(TaskEntity task, ApiException failure) {
        TaskEntity latest = taskMapper.selectById(task.getId());
        if (latest == null || !STARTABLE_TASK_STATUSES.contains(latest.getStatus())) {
            log.warn("plan failure not persisted, task already left startable states, taskId={}",
                    task.getId());
            return;
        }
        String stableClientCode = clientFailureCode(failure.code());
        String userFailureReason = ExecutionContentSanitizer.userFailureDescription(stableClientCode);
        latest.setFailureCode(stableClientCode);
        // 规划异常详情只保留在服务端日志，任务字段和群聊状态卡片只能使用受控说明。
        latest.setFailureReason(userFailureReason);
        // 计划路径错误来自本次 Planner 输出，不是用户数据不可修复错误；允许重新规划一次，
        // 让强化后的路径契约有机会在重试中生效。其他 4xx 计划异常仍不可自动重试。
        latest.setFailureRetryable(failure.status().is5xxServerError()
                || "TASK_PLAN_PATH_INVALID".equals(failure.code()));
        latest.setFailureOccurredAt(LocalDateTime.now(ZoneOffset.UTC));
        updateTaskStatus(latest, "FAILED");
        sendAgentCard(latest, "task-" + latest.getId(), "FAILED", null, userFailureReason);
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
        // 旧数据库中的步骤可能还没有 executionMode；保留两参数解析路径，避免把旧步骤
        // 或外部测试中的默认 Agent 解析误判为“缺少 Agent”。新步骤使用冻结的三参数路径。
        Optional<Agent> agent = step.getExecutionMode() == null || step.getExecutionMode().isBlank()
                ? agentRegistry.resolve(step.getAssignedAgentId(), step.getRole())
                : agentRegistry.resolve(step.getAssignedAgentId(), step.getRole(), step.getExecutionMode());
        if (agent.isEmpty()) {
            log.warn("NO_AGENT step skipped taskId={} stepId={} role={} agentId={}", task.getId(), step.getId(),
                    step.getRole(), step.getAssignedAgentId());
            markStepSkipped(task, step);
            ctx.clearInfrastructureFeedbackFor(step.getId());
            ctx.retryOf = null;
            return routeState(state, "next");
        }
        ctx.activeStepId = step.getId();
        ctx.activeRunId = null;
        markStepRunning(task, step);
        TaskRunEntity run = taskRunService.createForStep(task.getProjectId(), task.getId(), step.getId(),
                step.getRole(), step.getAssignedAgentId(), task.getCreatedBy(), ctx.retryOf);
        ctx.activeRunId = run.getId();
        ctx.lastRunId = run.getId();
        taskRunService.markRunning(run.getId());
        AgentRunOutcome feedback = ctx.feedbackFor(step.getId());
        AgentInput input = contextAssembler.assemble(task, step, phase, feedback, run.getId(), ctx.planResult,
                ctx.codingResult, ctx.testResult, ctx.groupContext, ctx.patchFailureCounts());
        AgentRunOutcome outcome = safeExecute(agent.get(), phase, input);
        for (WorkerToolExecution execution : WorkerExecutionTraceContext.drain(run.getId())) {
            taskRunService.appendWorkerToolExecution(run, execution);
        }
        if (phase == OrchestrationPhase.CODING && outcome.getOutcome() == RunOutcome.SUCCEEDED) {
            ctx.lastCodingRunId = run.getId();
        }
        if (phase == OrchestrationPhase.CODING && outcome.getCodingResult() != null) {
            ctx.codingResult = outcome.getCodingResult();
        } else if (phase == OrchestrationPhase.TESTING && outcome.getTestResult() != null) {
            ctx.testResult = outcome.getTestResult();
        }
        // TestAgent 的 Worker stdout/stderr 已经过执行端长度限制；再次经统一日志入口脱敏、分行
        // 持久化。公开日志不包含 Worker 原始完整日志，只保留受限、脱敏后的诊断内容。
        if (outcome.getTestResult() != null) {
            taskRunService.appendWorkerOutput(run, "STDOUT", outcome.getTestResult().getStdout());
            taskRunService.appendWorkerOutput(run, "STDERR", outcome.getTestResult().getStderr());
            taskRunService.appendVerificationResult(run, outcome.getTestResult());
        }
        taskRunService.appendAgentObservations(run, outcome.getObservations());
        // 先做纯状态机决策，再持久化本次 Run。这样 FAILED_QUALITY 仍保持真实失败事实，
        // 同时可把“已进入修复闭环”明确写入用户可见的 Run 消息，避免前端把单次 Run 失败
        // 误解为 Task 已经终止。
        StateMachineDecision decision = stateMachine.decide(phase, outcome.getOutcome(), outcome.getFailureCode(),
                ctx.counters);
        // 质量修复循环现在只由 REVIEWING 的 FAILED_QUALITY 触发（Test 不再自行判定失败，测试失败
        // 统一 TEST_FAILED 交 Review 裁决）。仍需 Review 明确声明失败是否可由 Coding 修复；旧 Agent/
        // 测试构造若没有结构化结果时保留历史兼容行为；一旦有结果且 needsCodingFix=false，直接终止，
        // 避免把测试环境、命令配置或不可修复问题反复送回 Coding。
        if (decision.getAction() == StateMachineDecision.Action.REQUEUE_CODING
                && !needsCodingFix(outcome)) {
            ctx.recordQualityRepairUnavailable("QUALITY_REPAIR_NOT_REQUESTED",
                    "质量检查未通过，但该检查结果标记为不能由开发步骤自动修复");
            decision = StateMachineDecision.failed();
        }
        // 只读任务可能没有任何可修复的 MUTATE 步骤。此时质量失败不能沿用
        // requeue 路由回到一个 VERIFY/TEST 节点，否则会重复验证同一事实直到耗尽循环。
        if (decision.getAction() == StateMachineDecision.Action.REQUEUE_CODING
                && !hasMutableStep(ctx.steps)) {
            ctx.recordQualityRepairUnavailable("QUALITY_REPAIR_STEP_UNAVAILABLE",
                    "质量检查未通过，但当前计划没有可写的 MUTATE 开发步骤可用于修复");
            decision = StateMachineDecision.failed();
        }
        // Review 判 BLOCKER/MAJOR 且测试因环境问题未执行时，不回 Coding：环境问题是执行环境缺陷
        // 而非本次代码可修复，若打回 Coding 会让任务反复「改代码→再测→又环境失败」空转。放行路径
        // （Review 判代码无误）仍走 COMPLETE_SUCCESS，终态卡片已如实标注「测试未通过/未执行原因」
        // （见 finishTask 的 testNotPassedNote）。
        if (decision.getAction() == StateMachineDecision.Action.REQUEUE_CODING
                && phase == OrchestrationPhase.REVIEWING
                && ctx.testResult != null && ctx.testResult.getEnvironmentFailureCode() != null
                && !ctx.testResult.getEnvironmentFailureCode().isBlank()) {
            ctx.recordQualityRepairUnavailable(ctx.testResult.getEnvironmentFailureCode(),
                    "测试因环境问题未执行（" + ctx.testResult.getEnvironmentFailureCode()
                            + "）；Review 兜底审查发现代码疑点，任务失败（环境问题不回 Coding）");
            decision = StateMachineDecision.failed();
        }
        // 质量循环不收敛：本轮与上一轮可修复项完全一致（无任何消减或变化）→ 提前终止，省下
        // 注定空转的循环预算（模型修不动或该 MAJOR 本身是误报时，再多打回也只会重复耗 LLM 调用）。
        // 有变化的循环（子集缩小/新增项/issue 变化）才记录本轮签名供下一轮比对。
        if (decision.getAction() == StateMachineDecision.Action.REQUEUE_CODING
                && ctx.qualityConvergence.hasNoProgress(outcome)) {
            ctx.qualityConvergence.markNoProgress();
            decision = StateMachineDecision.failed();
        } else if (decision.getAction() == StateMachineDecision.Action.REQUEUE_CODING) {
            ctx.qualityConvergence.record(outcome);
        }
        // Coding 自报失败但存在真实写入证据（如模型误判"无法确认文件是否创建"）时，
        // 给一次有界同相位重试：写入证据说明模型确实执行了修改，失败多为收尾误判而非
        // 真实不可完成。门控严格限定为「纯自报失败」——排除已归类的失败
        // （TOOL_PATCH_UNRECOVERABLE 等，failureCode 非空）与无任何写入的 no-op 自报失败
        // （后者维持立即终态，避免重试同一个已达到目标状态的 Coding 产生回环）。
        if (phase == OrchestrationPhase.CODING
                && decision.getAction() == StateMachineDecision.Action.COMPLETE_FAILED
                && outcome.getOutcome() == RunOutcome.FAILED
                && outcome.getFailureCode() == null
                && outcome.isHasRealChanges()
                && ctx.counters.canRetryCodingSelfReport()) {
            ctx.counters.incrementCodingSelfReportFailRetries();
            log.info("CODING_SELF_REPORT_FAILED_RETRY taskId={} stepId={} workspaceId={}",
                    task.getId(), step.getId(), input == null ? null : input.getWorkspaceId());
            decision = StateMachineDecision.retryPhase(OrchestrationPhase.CODING);
        }
        ctx.recordOutcome(step.getId(), phase, outcome);
        outcome.setMessage(runCompletionMessage(outcome.getMessage(), decision, ctx.counters));
        recordFailureDiagnostic(task, run, step, outcome);
        // AGENTS.md：Run 产物必须先成功落库，再发布 Run 终态事件；产物类型使用稳定相位名，
        // 不泄漏可扩展的 step role，保证前端时间线可识别 CODING/TESTING/REVIEWING。
        artifactService.createRunArtifact(task, run, step, artifactType(phase), runArtifactSummary(step, outcome));
        // 取消收敛：run 可能已在执行中被取消（RUNNING→CANCELLING）。此时结果由用户取消决定，
        // 不能把 outcome 决定的终态（FAILED/SUCCEEDED）覆盖上去，统一落 CANCELLED。
        TaskRunEntity latestRun = taskRunService.findById(run.getId());
        if (latestRun != null && "CANCELLING".equals(latestRun.getStatus())) {
            taskRunService.complete(run.getId(), "CANCELLED");
            markStepSettled(task, step, RunOutcome.CANCELLED);
            finishTask(task, ctx, StateMachineDecision.Action.COMPLETE_CANCELLED);
            return routeState(state, GraphDefinition.END);
        }
        taskRunService.complete(run.getId(), terminalStatus(outcome.getOutcome()), outcome.getFailureCode(),
                outcome.getMessage());
        // complete() 会在并发取消已先落库时把结果强制收敛为 CANCELLED；按持久化后的真实状态
        // 更新 Step，不能继续使用 Agent 原始的 SUCCEEDED/FAILED 结果。
        TaskRunEntity settledRun = taskRunService.findById(run.getId());
        markStepSettled(task, step, settledRun != null && "CANCELLED".equals(settledRun.getStatus())
                ? RunOutcome.CANCELLED : outcome.getOutcome());
        if (decision.getAction() == StateMachineDecision.Action.COMPLETE_SUCCESS
                && hasFollowingStep(step, ctx.steps)) {
            decision = StateMachineDecision.advance(phase);
        }
        String route;
        switch (decision.getAction()) {
            case ADVANCE -> {
                ctx.retryOf = null;
                // TESTING 相位失败（验证/测试未通过）→ 交下一个 REVIEW 步骤裁决，而不是按序列
                // next 继续执行后续步骤——否则 VERIFY 失败后仍会继续执行 MUTATE 写步骤
                // （Test 不判任务失败，Review 是最终裁决，见 OrchestrationStateMachine）。
                // TESTING SUCCEEDED 与其他相位仍正常按序推进。
                route = phase == OrchestrationPhase.TESTING
                        && outcome.getOutcome() != RunOutcome.SUCCEEDED ? "review" : "next";
            }
            case REQUEUE_CODING -> {
                resetStepsForQualityRework(task, ctx.steps, ctx.repairCodingStepId());
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

    private boolean needsCodingFix(AgentRunOutcome outcome) {
        if (outcome == null) {
            return false;
        }
        if (outcome.getPhase() == OrchestrationPhase.TESTING && outcome.getTestResult() != null) {
            return outcome.getTestResult().isNeedsCodingFix();
        }
        if (outcome.getPhase() == OrchestrationPhase.REVIEWING && outcome.getReviewResult() != null) {
            return outcome.getReviewResult().isNeedsCodingFix();
        }
        // 没有结构化质量结果时沿用状态机原有行为，兼容旧 Agent 与历史数据。
        return true;
    }

    /**
     * 单次质量检查失败与 Task 终态失败是两件不同的事实。Run 仍然如实为 FAILED，
     * 但已安排回修时必须让查询 Run 的客户端可识别后续会继续执行。
     */
    private String runCompletionMessage(String message, StateMachineDecision decision,
                                        OrchestrationCounters counters) {
        String base = message == null || message.isBlank() ? "质量检查未通过" : message;
        if (decision.getAction() != StateMachineDecision.Action.REQUEUE_CODING) {
            return base;
        }
        return base + "；已安排第" + counters.getQualityFixLoops() + "/"
                + counters.getMaxQualityFixLoops() + "次质量修复，将回到可写开发步骤并重新验证";
    }

    /**
     * step 角色 → 编排相位；自定义角色按序列位置推断：REVIEWER 步骤之前 → TESTING，
     * 之后/无 REVIEWER → REVIEWING（专项检查的失败语义挂测试或审查环节）。
     */
    private OrchestrationPhase stepPhase(TaskStepEntity step, List<TaskStepEntity> orderedSteps) {
        TaskStepExecutionMode mode = TaskStepExecutionMode.resolve(step.getExecutionMode(), step.getRole());
        // 执行模式是步骤的真实语义，优先于历史 role。尤其是 DEVELOPER/VERIFY：
        // 它是只读质量核验，失败时应进入 TESTING 的质量回路，而不是按 CODING 失败直接终止任务。
        if (mode == TaskStepExecutionMode.PLAN) {
            return OrchestrationPhase.PLAN;
        }
        if (mode == TaskStepExecutionMode.TEST || mode == TaskStepExecutionMode.VERIFY) {
            return OrchestrationPhase.TESTING;
        }
        if (mode == TaskStepExecutionMode.REVIEW) {
            return OrchestrationPhase.REVIEWING;
        }
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
                taskRunTimeoutExecutor.submit(() -> {
                    WorkerExecutionTraceContext.begin(input == null ? null : input.getTaskRunId());
                    try {
                        return agent.run(input);
                    } finally {
                        WorkerExecutionTraceContext.detach();
                    }
                });
        try {
            return future.get(limit.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true); // 尽力中断；阻塞 HTTP 未必被打断，由网络超时兜底
            log.warn("agent run timed out phase={} limit={}ms", phase, limit.toMillis());
            return infrastructureFailure(phase, "agent run timed out after " + limit.toSeconds() + "s",
                    "AGENT_RUN_TIMEOUT", "AGENT_TIMEOUT", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            return infrastructureFailure(phase, "agent execution failed: "
                    + (cause == null ? e.getMessage() : cause.getMessage()), llmFailureCode(cause), "AGENT_EXECUTION",
                    cause == null ? e : cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return infrastructureFailure(phase, "agent run interrupted", null, "AGENT_INTERRUPTED", e);
        } catch (RuntimeException e) {
            return infrastructureFailure(phase, "agent execution failed: " + e.getMessage(), llmFailureCode(e),
                    "AGENT_EXECUTION", e);
        }
    }

    /** 将供应商明确拒绝模型账号的响应转换为稳定、不可重试的业务错误码。 */
    private String llmFailureCode(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            String message = String.valueOf(current.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if (message.contains("access denied") && message.contains("account")) {
                return "LLM_ACCOUNT_ACCESS_DENIED";
            }
            if (message.contains("unauthorized") || message.contains("invalid api key")
                    || message.contains("authentication")) {
                return "LLM_AUTH_FAILED";
            }
            if (message.contains("insufficient_quota") || message.contains("billing")
                    || message.contains("payment required") || message.contains("quota exceeded")) {
                return "LLM_BILLING_REQUIRED";
            }
            if (message.contains("rate limit") || message.contains("too many requests")) {
                return "LLM_RATE_LIMITED";
            }
            if (message.contains("model") && (message.contains("not found")
                    || message.contains("does not exist") || message.contains("not available"))) {
                return "LLM_MODEL_NOT_FOUND";
            }
            if (message.contains("invalid request") || message.contains("invalid parameter")
                    || message.contains("bad request")) {
                return "LLM_REQUEST_INVALID";
            }
            if (message.contains("timeout") || message.contains("timed out")) {
                return "LLM_TIMEOUT";
            }
            if (message.contains("connection refused") || message.contains("connection reset")
                    || message.contains("network is unreachable") || message.contains("dns")) {
                return "LLM_NETWORK_FAILED";
            }
            if (message.contains("service unavailable") || message.contains("bad gateway")
                    || message.contains("temporarily unavailable")) {
                return "LLM_SERVICE_UNAVAILABLE";
            }
        }
        return null;
    }

    private AgentRunOutcome infrastructureFailure(OrchestrationPhase phase, String message, String failureCode) {
        return infrastructureFailure(phase, message, failureCode, "AGENT_OUTCOME", null);
    }

    private AgentRunOutcome infrastructureFailure(OrchestrationPhase phase, String message, String failureCode,
                                                  String source, Throwable cause) {
        AgentRunOutcome failure = new AgentRunOutcome();
        failure.setPhase(phase);
        failure.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
        failure.setMessage(message);
        failure.setFailureCode(failureCode);
        failure.setDiagnosticSource(source);
        failure.setDiagnosticFailureCode(cause instanceof ApiException api ? api.code() : failureCode);
        failure.setDiagnosticExceptionType(cause == null ? null : cause.getClass().getSimpleName());
        failure.setDiagnosticDetail(cause == null ? message : cause.getMessage());
        return failure;
    }

    /**
     * 任一失败 Run 的诊断必须在执行产物和 Run 终态之前完成。
     */
    private void recordFailureDiagnostic(TaskEntity task, TaskRunEntity run, TaskStepEntity step,
                                         AgentRunOutcome outcome) {
        if (step == null) {
            log.warn("failure diagnostic skipped because task step is missing, taskRunId={}",
                    run == null ? null : run.getId());
            return;
        }
        failureDiagnostics.record(task, run, step, outcome == null ? null : outcome.getPhase(), outcome);
    }

    /** 保留既有客户端错误码契约；仅将内部别名折叠为已发布的稳定码。 */
    private String clientFailureCode(String code) {
        String publicCode = ExecutionContentSanitizer.publicFailureCode(code);
        return publicCode == null ? "FAILED_INFRASTRUCTURE" : publicCode;
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
     * Run 级执行产物的脱敏摘要：角色、执行模式、终态、Agent 反馈消息与每轮 LLM 观测。观测经
     * {@link LlmObservation#toSummary()} 序列化为脱敏 Map（仅 phase/round/字符数/结束原因/
     * 工具名/错误码/sha256），路径与敏感键由服务端 sanitize 兜底。
     */
    private Map<String, Object> runArtifactSummary(TaskStepEntity step, AgentRunOutcome outcome) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("role", step.getRole());
        // role 是 Agent 选择标签，executionMode 才是本次节点的权限/成功语义；两者同时
        // 持久化，便于定位“DEVELOPER 但实际是 VERIFY”的历史流程问题。
        summary.put("executionMode", TaskStepExecutionMode.resolve(step.getExecutionMode(), step.getRole()).name());
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
        if (outcome.getPatchFailureCounts() != null && !outcome.getPatchFailureCounts().isEmpty()) {
            summary.put("patchFailureCounts", outcome.getPatchFailureCounts());
        }
        if (outcome.getTestResult() != null && !outcome.getTestResult().isSuccess()) {
            summary.put("testFailure", testFailureSummary(outcome.getTestResult()));
        }
        if ("REVIEWER".equals(step.getRole()) && outcome.getReviewResult() != null) {
            summary.put("review", reviewSummary(outcome.getReviewResult()));
        }
        return summary;
    }

    /**
     * 面向项目成员展示的测试失败事实。只保存限长、脱敏后的失败项，不携带原始命令或 stdout/stderr。
     */
    private Map<String, Object> testFailureSummary(TestResult test) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("verificationMode", test.getVerificationMode());
        value.put("exitCode", test.getExitCode());
        value.put("needsCodingFix", test.isNeedsCodingFix());
        if (test.getEnvironmentFailureCode() != null && !test.getEnvironmentFailureCode().isBlank()) {
            value.put("environmentFailureCode", test.getEnvironmentFailureCode());
        }
        List<TestResult.Failure> allFailures = test.getFailures() == null ? List.of() : test.getFailures();
        List<Map<String, Object>> failures = allFailures.stream().filter(java.util.Objects::nonNull).limit(4).map(failure -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("name", safeFailureText(failure.getName(), 160));
                    item.put("reason", safeFailureText(failure.getReason(), 384));
                    item.put("severity", safeFailureText(failure.getSeverity(), 32));
                    return item;
                }).toList();
        value.put("failureCount", allFailures.size());
        value.put("failures", failures);
        return value;
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
        result.put("testsNotExecuted", review.isTestsNotExecuted());
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

    /** 公开失败项不得承载 Worker 原始命令、输出、环境变量、端点或宿主路径。 */
    private String safeFailureText(String value, int max) {
        return truncate(ExecutionContentSanitizer.sanitizeDiagnosticDetail(value == null ? "" : value).strip(), max);
    }

    private String lastDeveloperNodeId(List<TaskStepEntity> steps) {
        for (int index = steps.size() - 1; index >= 0; index--) {
            TaskStepEntity step = steps.get(index);
            TaskStepExecutionMode mode = TaskStepExecutionMode.resolve(step.getExecutionMode(), step.getRole());
            // 质量失败只能回到真正允许修改的 MUTATE 节点。历史实现只看 role，
            // 会把 DEVELOPER/VERIFY 误选为修复节点，导致质量循环再次执行只读核验。
            if (mode == TaskStepExecutionMode.MUTATE) {
                return step.getId().toString();
            }
        }
        return steps.get(0).getId().toString();
    }

    private boolean hasMutableStep(List<TaskStepEntity> steps) {
        return steps.stream().anyMatch(step -> TaskStepExecutionMode
                .resolve(step.getExecutionMode(), step.getRole()) == TaskStepExecutionMode.MUTATE);
    }

    /**
     * 质量失败进入修复闭环后，先撤销本轮以及后续验证步骤的展示终态。TaskRun 仍保留
     * 不可变的历史成功/失败事实；TaskStep 仅表达当前代码版本下一轮尚待执行的状态。
     */
    private void resetStepsForQualityRework(TaskEntity task, List<TaskStepEntity> steps, UUID repairStepId) {
        if (repairStepId == null) {
            log.warn("quality rework skipped step reset because no mutable step exists, taskId={}", task.getId());
            return;
        }
        int startIndex = indexOfStepId(steps, repairStepId);
        if (startIndex < 0) {
            log.warn("quality rework skipped step reset because repair step is absent, taskId={}, stepId={}",
                    task.getId(), repairStepId);
            return;
        }
        for (int index = startIndex; index < steps.size(); index++) {
            TaskStepEntity latest = stepMapper.selectById(steps.get(index).getId());
            if (latest == null) {
                log.warn("quality rework skipped missing step, taskId={}, stepId={}", task.getId(),
                        steps.get(index).getId());
                continue;
            }
            latest.setStatus("PENDING");
            latest.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            stepMapper.updateById(latest);
            publishStepUpdated(task, latest);
        }
    }

    private int indexOfStepId(List<TaskStepEntity> steps, UUID stepId) {
        for (int index = 0; index < steps.size(); index++) {
            if (stepId.equals(steps.get(index).getId())) {
                return index;
            }
        }
        return -1;
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
        step.setStatus(stepStatus(outcome));
        step.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        stepMapper.updateById(step);
        publishStepUpdated(task, step);
        sendAgentCard(task, "step-" + step.getId(), step.getStatus(), step.getRole(), stepSettledMessage(step));
    }

    /**
     * Run 终态 → TaskStep 状态。取消（RunOutcome.CANCELLED）必须落 CANCELLED，
     * 不能降级为 FAILED——否则前端「已取消」状态永远收不到，且会被计入失败统计。
     */
    private String stepStatus(RunOutcome outcome) {
        return switch (outcome == null ? RunOutcome.FAILED : outcome) {
            case SUCCEEDED -> "SUCCEEDED";
            case CANCELLED -> "CANCELLED";
            default -> "FAILED";
        };
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
        // 并发取消护栏：编排器走到终态时，任务可能已被用户取消（CANCELLING/CANCELLED）。
        // 重查最新状态，避免用启动时缓存的内存对象把刚写入的 CANCELLING 全行覆盖回 FAILED/SUCCEEDED。
        TaskEntity latest = taskMapper.selectById(task.getId());
        if (latest != null && ("CANCELLING".equals(latest.getStatus()) || "CANCELLED".equals(latest.getStatus()))) {
            // 取消后当前正在执行的 run 不再产生真实结果，随任务一并落 CANCELLED 终态，避免遗留 RUNNING。
            settleRunForCancellation(ctx);
            cancelRemainingSteps(ctx);
            if (!"CANCELLED".equals(latest.getStatus())) {
                // CANCELLING → CANCELLED 收敛：取消已受理且编排到达终态点，落终态。
                updateTaskStatus(latest, "CANCELLED");
                sendAgentCard(latest, "task-" + latest.getId(), "CANCELLED", null, "任务已取消");
            }
            return;
        }
        // 恢复器可能已经把卡死启动窗口收敛为 FAILED；旧线程晚返回时不得用过期上下文
        // 覆盖任务终态，也不得重复发送成功/失败卡片。
        if (latest == null || !STARTABLE_TASK_STATUSES.contains(latest.getStatus())) {
            log.info("skip stale orchestration terminal update taskId={} status={}", task.getId(),
                    latest == null ? "MISSING" : latest.getStatus());
            return;
        }
        FinishingStatus finishing = switch (action) {
            case COMPLETE_SUCCESS -> completeSuccess(task, ctx);
            case COMPLETE_CANCELLED -> new FinishingStatus("CANCELLED", null, null);
            default -> new FinishingStatus("FAILED", null, null);
        };
        if ("FAILED".equals(finishing.status())) {
            TaskRunEntity failedRun = ctx.lastRunId == null ? null : taskRunService.findById(ctx.lastRunId);
            String code = finishing.failureCode();
            String reason = finishing.failureReason();
            if ((code == null || code.isBlank()) && failedRun != null && failedRun.getFailureCode() != null) {
                code = failedRun.getFailureCode();
                reason = failedRun.getFailureReason();
            }
            // 失败 run 未携带稳定失败码（如质量循环中的 Test/Review FAILED_QUALITY 无码）时，
            // 按任务级语义收敛：质量修复循环已耗尽 → 明确「质量循环耗尽」，而不是误导性的
            // TASK_FINALIZATION_FAILED（交付准备失败）。
            if ((code == null || code.isBlank()) && ctx.qualityRepairUnavailable != null) {
                code = ctx.qualityRepairUnavailable.code();
                reason = ctx.qualityRepairUnavailable.reason();
            }
            if (code == null || code.isBlank()) {
                code = ctx.counters.getQualityFixLoops() > 0 ? "TASK_QUALITY_LOOPS_EXHAUSTED"
                        : "TASK_FINALIZATION_FAILED";
                if (reason == null || reason.isBlank()) {
                    // 不收敛提前终止与循环耗尽用同一失败码（避免接口契约新增），但文案如实区分：
                    // 前者是"连续多轮无进展主动叫停"，后者才是"循环额度用完"。
                    reason = ctx.counters.getQualityFixLoops() > 0
                            ? (ctx.qualityConvergence.noProgressTerminated()
                                    ? "任务连续多轮质量验证未见修复进展，提前终止修复循环"
                                    : "任务多次未通过质量验证，修复循环已耗尽")
                            : "任务在执行完成阶段失败，可查看任务诊断";
                }
            }
            String publicFailureCode = clientFailureCode(code);
            task.setFailureCode(publicFailureCode);
            if (ctx.qualityConvergence.noProgressTerminated()) {
                // 不收敛提前终止与循环耗尽共用 TASK_QUALITY_LOOPS_EXHAUSTED（不新增契约失败码），
                // 但文案如实区分"连续多轮无进展主动叫停"与"循环额度用完"。
                task.setFailureReason("任务连续多轮质量验证未见修复进展，提前终止修复循环");
            } else {
                task.setFailureReason(ExecutionContentSanitizer.userFailureDescription(publicFailureCode));
            }
            task.setFailureRetryable(true);
            task.setFailureOccurredAt(LocalDateTime.now(ZoneOffset.UTC));
        } else if ("SUCCEEDED".equals(finishing.status()) || "DELIVERING".equals(finishing.status())) {
            task.setFailureCode(null);
            task.setFailureReason(null);
            task.setFailureRetryable(null);
            task.setFailureOccurredAt(null);
        }
        updateTaskStatus(task, finishing.status());
        if (finishing.reviewBatchId() != null) {
            sendDiffCard(task, finishing.reviewBatchId());
        }
        String cardMessage = taskResultMessage(finishing);
        // Review 放行但测试未真实通过时，终态如实标注原因，不得描述为测试通过。
        // 环境阻塞/未检测到测试命令/执行超时属「测试未完成验证」（TestResult.isInconclusive()），
        // 其余是测试真实执行并给出失败结论（如代码缺陷失败但 Review 判定无 BLOCKER/MAJOR）。
        if (action == StateMachineDecision.Action.COMPLETE_SUCCESS && !"FAILED".equals(finishing.status())
                && ctx.testResult != null && !ctx.testResult.isSuccess()) {
            String note = testNotPassedNote(ctx.testResult);
            if (note != null) {
                cardMessage = cardMessage + "；代码审查通过，但" + note;
            }
        }
        sendAgentCard(task, "task-" + task.getId(), finishing.status(), null, cardMessage);
    }

    /**
     * 取消收敛时把当前正在执行的 run 落 CANCELLED：任务被取消后，其进行中的 run 不再产生
     * 真实结果，随任务一并收敛，避免 run 永久遗留 RUNNING/CANCELLING。
     */
    private void settleRunForCancellation(TaskExecutionContext ctx) {
        if (ctx == null || ctx.lastRunId == null) {
            return;
        }
        try {
            TaskRunEntity run = taskRunService.findById(ctx.lastRunId);
            if (run != null && ("RUNNING".equals(run.getStatus()) || "CANCELLING".equals(run.getStatus()))) {
                taskRunService.complete(run.getId(), "CANCELLED");
            }
        } catch (RuntimeException e) {
            log.warn("settle cancelled run skipped taskId={} runId={}: {}", ctx.task.getId(), ctx.lastRunId,
                    e.getMessage());
        }
    }

    /**
     * 任务取消收敛：把尚未执行的 PENDING 步骤一并置 CANCELLED，避免「任务已取消」但
     * 后续步骤仍停留在 PENDING（待执行）的矛盾状态。只覆盖 PENDING，不触碰已终态步骤。
     */
    private void cancelRemainingSteps(TaskExecutionContext ctx) {
        if (ctx == null || ctx.steps == null) {
            return;
        }
        for (TaskStepEntity step : ctx.steps) {
            if (!"PENDING".equals(step.getStatus())) {
                continue;
            }
            try {
                TaskStepEntity latest = stepMapper.selectById(step.getId());
                if (latest == null || !"PENDING".equals(latest.getStatus())) {
                    continue;
                }
                latest.setStatus("CANCELLED");
                latest.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                stepMapper.updateById(latest);
                publishStepUpdated(ctx.task, latest);
                sendAgentCard(ctx.task, "step-" + latest.getId(), "CANCELLED", latest.getRole(),
                        roleLabel(latest.getRole()) + "步骤已取消");
            } catch (RuntimeException e) {
                log.warn("cancel remaining step skipped taskId={} stepId={}: {}", ctx.task.getId(),
                        step.getId(), e.getMessage());
            }
        }
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
            return new FinishingStatus("FAILED", null, null, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("mr-first batch creation failed, task finishes FAILED, taskId={}: {}",
                    task.getId(), e.getMessage(), e);
            return new FinishingStatus("FAILED", null, null, "TASK_FINALIZATION_FAILED", "任务交付准备失败");
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
            return new FinishingStatus("FAILED", null, null, e.code(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("final diff batch creation failed, task finishes FAILED, taskId={}: {}",
                    task.getId(), e.getMessage(), e);
            return new FinishingStatus("FAILED", null, null, "TASK_FINALIZATION_FAILED", "任务交付准备失败");
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
        enrichRepositoryContext(task, content, idSuffix);
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

    /**
     * 在既有 TASK_STATUS content 中补充结构化仓库映射；服务不可用时降级为空数组，
     * 不让卡片增强逻辑影响任务真实状态。
     */
    private void enrichRepositoryContext(TaskEntity task, Map<String, Object> content, String idSuffix) {
        if (repositoryContextService == null || task == null || content == null) {
            return;
        }
        try {
            content.put("repositoryMappings", repositoryContextService.allRepositories(task));
            Object rawStepId = content.get("currentStepId");
            UUID stepId = rawStepId == null ? null : UUID.fromString(String.valueOf(rawStepId));
            if (stepId == null && idSuffix != null && idSuffix.startsWith("step-")) {
                stepId = UUID.fromString(idSuffix.substring("step-".length()));
            }
            content.put("currentRepositoryPaths",
                    repositoryContextService.currentPathsForStep(task, stepId));
        } catch (RuntimeException failure) {
            log.warn("repository context omitted from task card taskId={}: {}",
                    task.getId(), failure.getMessage());
            content.put("repositoryMappings", List.of());
            content.put("currentRepositoryPaths", List.of());
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
            case "CANCELLED" -> roleLabel(step.getRole()) + "步骤已取消";
            default -> roleLabel(step.getRole()) + "步骤失败，已按重试或修复策略处理";
        };
    }

    /**
     * 任务结果卡片文案：按终态表达交付确认、完成、失败或取消。
     */
    private String taskResultMessage(FinishingStatus finishing) {
        if ("FAILED".equals(finishing.status())) {
            return "任务执行失败：" + ExecutionContentSanitizer.userFailureDescription(
                    clientFailureCode(finishing.failureCode()));
        }
        if (finishing.message() != null) {
            return finishing.message();
        }
        return switch (finishing.status()) {
            case WAITING_DIFF_CONFIRMATION -> "任务开发完成，等待你对 Diff 的确认";
            case "DELIVERING" -> "任务开发完成，正在提交并推送代码";
            case "WAITING_PREFLIGHT" -> "代码已推送，等待 MR 前预检";
            case "SUCCEEDED" -> "任务已完成";
            case "CANCELLED" -> "任务已取消";
            default -> "任务状态更新：" + finishing.status();
        };
    }

    /**
     * Review 放行但测试未真实通过时，按失败类型给出如实标注原因；返回 null 表示无附加说明。
     * 环境阻塞/未检测到测试命令/执行超时属「测试未完成验证」（见 {@link TestResult#isInconclusive()}），
     * 其余是测试真实执行并给出失败结论（如代码缺陷失败但 Review 判定无 BLOCKER/MAJOR）。
     * 终态一律不得描述为测试通过。
     */
    private String testNotPassedNote(TestResult test) {
        if (test.getEnvironmentFailureCode() != null && !test.getEnvironmentFailureCode().isBlank()) {
            return "测试因环境问题未执行（" + test.getEnvironmentFailureCode() + "）";
        }
        if ("NONE".equalsIgnoreCase(test.getVerificationMode())) {
            return "未检测到可执行的测试命令，测试未执行";
        }
        if (test.isInconclusive()) {
            return "测试执行超时，未完成验证";
        }
        return "测试未通过（exit code " + test.getExitCode() + "）";
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

    private record StartupFailure(String code, String title, String reason, boolean retryable) {
    }

    /**
     * 任务到达终态时向发起人写入通知（A 联调约定 §1）。
     * 仅 SUCCEEDED/FAILED 有对应 kind；CANCELLED 不在约定映射内，不写入。
     */
    private void notifyTaskTerminal(TaskEntity task, String status) {
        String kind = switch (status) {
            case "SUCCEEDED" -> "TASK_COMPLETED";
            case "FAILED" -> "TASK_FAILED";
            case "WAITING_DIFF_CONFIRMATION" -> "TASK_AWAITING_CONFIRMATION";
            default -> null;
        };
        if (kind == null) {
            return;
        }
        notificationService.notify(task.getCreatedBy(), task.getProjectId(), task.getRequirementGroupId(), kind,
                (kind.equals("TASK_COMPLETED") ? "任务完成："
                        : kind.equals("TASK_FAILED") ? "任务失败："
                        : "任务 Diff 待确认：") + task.getTitle(),
                kind.equals("TASK_AWAITING_CONFIRMATION") ? "已生成待确认 Diff，请前往任务详情确认或拒绝交付" : task.getRequirement(),
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
    private record FinishingStatus(String status, UUID reviewBatchId, String message,
                                   String failureCode, String failureReason) {
        private FinishingStatus(String status, UUID reviewBatchId, String message) {
            this(status, reviewBatchId, message, null, null);
        }
    }

    /**
     * 一次 orchestrate 会话内的执行现场：跨节点传递的富结果、循环反馈、最近 TaskRun、
     * 有序步骤与循环计数。与图状态解耦，仅在进程内按 taskId 暂存，invoke 结束后由 orchestrate 清理。
     */
    private static final class TaskExecutionContext {
        private final TaskEntity task;
        private final OrchestrationCounters counters = new OrchestrationCounters();
        /** 质量循环不收敛判定：记录上一轮可修复项签名，识别"修不动"的循环并提前终止。 */
        private final QualityConvergenceTracker qualityConvergence = new QualityConvergenceTracker();
        private List<TaskStepEntity> steps;
        /** 质量反馈只定向给原失败 step 与被 requeue 的 Coding step。 */
        private QualityFeedback qualityFeedback;
        /**
         * 各相位最近一次基础设施失败，仅在该相位重试时优先回灌；不覆盖仍待复核的质量反馈。
         */
        private final java.util.Map<UUID, AgentRunOutcome> infraFeedback = new java.util.HashMap<>();
        /** 当前正在启动或执行的步骤，供异常补偿使用。 */
        private UUID activeStepId;
        /** 当前步骤的 TaskRun；创建成功后立即记录，覆盖 QUEUED/RUNNING 异常窗口。 */
        private UUID activeRunId;
        private UUID lastRunId;
        /** 启动窗口被恢复器或异常收敛后，旧线程不得继续进入图执行。 */
        private volatile boolean aborted;
        private UUID retryOf;
        /**
         * 本次续跑的起始步骤 ID（用户重试/恢复器续跑传入）；null 表示全量编排。
         * 用于启动阶段失败时把失败 run 关联到正确的步骤，避免退化成 PLANNER 步骤。
         */
        private UUID startStepId;
        /**
         * 最后一次 SUCCEEDED 的 CODING run，终态时供 FinalDiffBundleService 生成待确认 Diff 批次。
         */
        private UUID lastCodingRunId;
        private PlanResult planResult;
        private CodingResult codingResult;
        private TestResult testResult;
        /** 当前 TaskStep 跨 TaskRun 继承的 patch 失败计数。 */
        private final Map<String, Integer> patchFailureCounts = new LinkedHashMap<>();
        /**
         * 质量失败本应回修但无法路由到可写步骤时的明确终止原因。它优先于循环计数，
         * 避免把“根本没有修复入口”误报成“多次修复后仍失败”。
         */
        private QualityRepairUnavailable qualityRepairUnavailable;
        /**
         * 本次 orchestrate 快照的群聊/Skill/Memory 上下文，跨节点复用；组装失败时为 null（不阻断）。
         */
        private GroupContext groupContext;

        private TaskExecutionContext(TaskEntity task) {
            this.task = task;
        }

        private void inheritPatchFailureCounts(Map<String, Integer> counts) {
            if (counts != null) {
                counts.forEach((path, count) -> {
                    if (path != null && !path.isBlank() && count != null && count > 0) {
                        patchFailureCounts.put(path, Math.min(count, 3));
                    }
                });
            }
        }

        private Map<String, Integer> patchFailureCounts() {
            return Map.copyOf(patchFailureCounts);
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
            if (phase == OrchestrationPhase.CODING && outcome.getPatchFailureCounts() != null) {
                patchFailureCounts.clear();
                inheritPatchFailureCounts(outcome.getPatchFailureCounts());
            }
            // 基础设施失败与"有真实写入证据的自报失败"都作为同相位重试反馈暂存：
            // 后者由 runStepNode 的证据门控重试时消费，让重试 run 能读到上一轮失败原因。
            boolean selfReportFailRetry = phase == OrchestrationPhase.CODING
                    && outcome.getOutcome() == RunOutcome.FAILED
                    && outcome.getFailureCode() == null
                    && outcome.isHasRealChanges();
            if (outcome.getOutcome() == RunOutcome.FAILED_INFRASTRUCTURE || selfReportFailRetry) {
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
                // 质量相位通过即结束本段闭环，清除不收敛签名，避免跨环节残留比对。
                qualityConvergence.clear();
            }
        }

        private void clearInfrastructureFeedbackFor(UUID stepId) {
            infraFeedback.remove(stepId);
        }

        private void recordQualityRepairUnavailable(String code, String reason) {
            qualityRepairUnavailable = new QualityRepairUnavailable(code, reason);
        }

        private UUID repairCodingStepId() {
            if (steps == null) {
                return null;
            }
            UUID mutableStepId = null;
            for (TaskStepEntity step : steps) {
                if (TaskStepExecutionMode.resolve(step.getExecutionMode(), step.getRole())
                        == TaskStepExecutionMode.MUTATE) {
                    mutableStepId = step.getId();
                }
            }
            return mutableStepId;
        }

        private record QualityFeedback(UUID sourceStepId, UUID repairCodingStepId, AgentRunOutcome outcome) {
        }

        private record QualityRepairUnavailable(String code, String reason) {
        }
    }
}
