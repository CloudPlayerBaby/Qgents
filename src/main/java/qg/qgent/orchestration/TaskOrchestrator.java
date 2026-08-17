package qg.qgent.orchestration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.dto.GroupContext;
import qg.qgent.dto.MessageSendRequest;
import qg.qgent.dto.TaskRepositoryScopeRequest;
import qg.qgent.dto.TaskStepCreateRequest;
import qg.qgent.entity.*;
import qg.qgent.mapper.*;
import qg.qgent.orchestration.llm.LlmObservation;
import qg.qgent.orchestration.result.CodingResult;
import qg.qgent.orchestration.result.PlanResult;
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
 * 节点（PLANNER 提升为带 TaskRun 的正式 step），节点执行 {@link #runStepNode}——建 TaskRun、
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
    private static final Set<String> STARTABLE_TASK_STATUSES = Set.of("PLANNING", "PENDING", "RUNNING");

    /**
     * 模板步骤的通用指令（PLANNER 产出计划后回填 DEVELOPER/TESTER 指令；
     * 已回填或用户自写的指令不再覆盖）。
     */
    private static final String TEMPLATE_PLANNER_INSTRUCTION = "分析需求并制定实现计划";
    private static final String TEMPLATE_DEVELOPER_INSTRUCTION = "实现任务需求：读取相关代码、按需修改工作区文件，并完成自检";
    private static final String TEMPLATE_TESTER_INSTRUCTION = "运行测试并判定是否满足验收";
    private static final String TEMPLATE_REVIEWER_INSTRUCTION = "审查本次改动是否符合需求、质量与安全要求";

    private static final Logger log = LoggerFactory.getLogger(TaskOrchestrator.class);
    /**
     * 终态中等待用户确认的 Diff 审核状态；确认后的交付由 DiffReviewBatchService 驱动。
     */
    private static final String WAITING_DIFF_CONFIRMATION = "WAITING_DIFF_CONFIRMATION";

    private final OrchestrationStateMachine stateMachine;
    private final WorkflowGraphBuilder workflowGraphBuilder;
    private final AgentRegistry agentRegistry;
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
    private final OrchestratorAgentService orchestratorAgents;

    /**
     * 各编排任务的执行现场（富结果/反馈/计数，不进图状态），按 taskId 暂存。
     */
    private final Map<UUID, TaskExecutionContext> executions = new ConcurrentHashMap<>();

    public TaskOrchestrator(OrchestrationStateMachine stateMachine, WorkflowGraphBuilder workflowGraphBuilder,
                            AgentRegistry agentRegistry, AgentContextAssembler contextAssembler, TaskService taskService,
                            TaskRunService taskRunService, TaskMapper taskMapper, TaskStepMapper stepMapper,
                            WorkspaceRepositoryMapper workspaceRepositoryMapper, EventService eventService,
                            NotificationService notificationService, SandboxSessionManager sandboxSessionManager,
                            TaskExecutionArtifactService artifactService, FinalDiffBundleService finalDiffBundles,
                            MessageService messageService, AgentMapper agentMapper, ProjectMapper projectMapper,
                            OrchestratorAgentService orchestratorAgents) {
        this.stateMachine = stateMachine;
        this.workflowGraphBuilder = workflowGraphBuilder;
        this.agentRegistry = agentRegistry;
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
        this.orchestratorAgents = orchestratorAgents;
    }

    /**
     * 同步驱动一个 Task 的完整编排链路：先确保步骤就位（模板 + 用户配置合并，resolveAgent
     * 落 assignedAgentId），再按步骤数据驱动建图并沿条件边执行，直到任一节点决策到达终态
     * （END）。整条链路同步跑完。
     * <p>
     * 入口为整条链路准备一次 Sandbox 会话（Worker 端口启用时），并在终态后释放；
     * 未启用 Worker 时会话管理器为 no-op，不影响本地端口链路。
     *
     * @param projectId 项目 ID（Task 归属校验）
     * @param taskId    要编排的 Task ID
     * @throws IllegalStateException Task 不存在、不属于该项目或状态不可启动时抛出
     */
    public void orchestrate(UUID projectId, UUID taskId) {
        log.info("orchestrate start taskId={} projectId={}", taskId, projectId);
        TaskEntity task = requireTask(projectId, taskId);
        requireStartable(task);
        TaskExecutionContext ctx = new TaskExecutionContext(task);
        executions.put(taskId, ctx);
        try {
            sandboxSessionManager.acquire(task.getId(), task.getProjectId(), task.getWorkspaceId());
            List<TaskStepEntity> steps = ensureSteps(task);
            ctx.steps = steps;
            // 群聊/Skill/Memory 上下文快照：一次 orchestrate 组装一次，跨节点复用（失败不阻断）
            ctx.groupContext = contextAssembler.buildGroupContext(task);
            CompiledGraph<TaskOrchestrationState> graph = workflowGraphBuilder.build(steps,
                    (step, state) -> runStepNode(step, state), developerNodeId(steps));
            log.info("orchestrate sandbox acquired taskId={}", taskId);
            graph.invoke(Map.of("projectId", projectId.toString(), "taskId", taskId.toString()));
            log.info("orchestrate graph completed taskId={}", taskId);
        } catch (RuntimeException e) {
            // 启动/图执行阶段的意外失败（Sandbox Worker 不可达、建图失败等）必须落 FAILED 终态并
            // 通知用户，不允许任务无声卡死在初始状态；requireTask/requireStartable 的幂等护栏
            // 异常在 try 之外，继续外抛由监听器吞掉。
            failStartup(task, e);
        } finally {
            sandboxSessionManager.release(task.getWorkspaceId());
            executions.remove(taskId);
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
     * 确保任务步骤就位（幂等，只补缺不重复创建）：任务无步骤时按模板创建
     * [PLANNER, DEVELOPER, TESTER, REVIEWER]；已有步骤时保留用户步骤与 assignedAgentId，
     * 仅补缺——缺 PLANNER 则补建（恒在首位）、缺 DEVELOPER 则补建（requeue 目标必须存在），
     * 用户步骤缺失 TESTER/REVIEWER 视为用户选择。返回按执行顺序排列的步骤。
     */
    private List<TaskStepEntity> ensureSteps(TaskEntity task) {
        List<TaskStepEntity> existing = loadSteps(task.getId());
        if (existing.isEmpty()) {
            createTemplateSteps(task);
            return normalizeSteps(task, loadSteps(task.getId()));
        }
        List<TaskStepCreateRequest> toAdd = new ArrayList<>();
        if (findByRole(existing, "PLANNER") == null) {
            toAdd.add(gapStep(task, "PLANNER", "Plan", TEMPLATE_PLANNER_INSTRUCTION, "READ"));
        }
        if (findByRole(existing, "DEVELOPER") == null) {
            toAdd.add(gapStep(task, "DEVELOPER", "Implement", TEMPLATE_DEVELOPER_INSTRUCTION, "WRITE"));
        }
        if (!toAdd.isEmpty()) {
            taskService.addSteps(task.getProjectId(), task.getId(), task.getCreatedBy(), toAdd);
        }
        return normalizeSteps(task, loadSteps(task.getId()));
    }

    /**
     * 无步骤任务：一次性创建模板四步（依赖链 PLANNER→DEVELOPER→TESTER→REVIEWER，
     * 仓库 scope DEVELOPER=WRITE、其余 READ），resolveAgent 在落库时定型 assignedAgentId。
     */
    private void createTemplateSteps(TaskEntity task) {
        List<UUID> repoIds = repositoryIds(task);
        UUID plannerId = UuidV7.next();
        UUID devId = UuidV7.next();
        UUID testId = UuidV7.next();
        UUID reviewId = UuidV7.next();
        List<TaskStepCreateRequest> requests = List.of(
                planStep(plannerId, "Plan", TEMPLATE_PLANNER_INSTRUCTION, "PLANNER", resolveAgent(task, "PLANNER"),
                        List.of(), "READ", repoIds),
                planStep(devId, "Implement", TEMPLATE_DEVELOPER_INSTRUCTION, "DEVELOPER",
                        resolveAgent(task, "DEVELOPER"), List.of(plannerId), "WRITE", repoIds),
                planStep(testId, "Verify", TEMPLATE_TESTER_INSTRUCTION, "TESTER", resolveAgent(task, "TESTER"),
                        List.of(devId), "READ", repoIds),
                planStep(reviewId, "Review", TEMPLATE_REVIEWER_INSTRUCTION, "REVIEWER",
                        resolveAgent(task, "REVIEWER"), List.of(testId), "READ", repoIds));
        taskService.addSteps(task.getProjectId(), task.getId(), task.getCreatedBy(), requests);
    }

    /**
     * 已有步骤的补缺单步（PLANNER/DEVELOPER），仓库 scope 按角色（PLANNER=READ，DEVELOPER=WRITE）。
     */
    private TaskStepCreateRequest gapStep(TaskEntity task, String role, String title, String instruction,
                                          String accessMode) {
        return planStep(UuidV7.next(), title, instruction, role, resolveAgent(task, role), List.of(), accessMode,
                repositoryIds(task));
    }

    /**
     * 步骤排序归一：PLANNER 恒在首位（fill-gap 补建的 PLANNER 在 DB 中 sequenceNo 靠后），
     * 其余按 sequenceNo 升序；随后把 DB sequenceNo 重排为 1..N，使 UI 展示与图执行顺序一致。
     */
    private List<TaskStepEntity> normalizeSteps(TaskEntity task, List<TaskStepEntity> steps) {
        List<TaskStepEntity> ordered = new ArrayList<>(steps);
        ordered.sort(Comparator
                .comparingInt((TaskStepEntity step) -> "PLANNER".equals(step.getRole()) ? 0 : 1)
                .thenComparingInt(step -> step.getSequenceNo() == null ? Integer.MAX_VALUE : step.getSequenceNo()));
        renumberSequences(task, ordered);
        return ordered;
    }

    private void renumberSequences(TaskEntity task, List<TaskStepEntity> ordered) {
        int seq = 1;
        for (TaskStepEntity step : ordered) {
            Integer current = step.getSequenceNo();
            if (current != null && current == seq) {
                seq++;
                continue;
            }
            step.setSequenceNo(seq++);
            step.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            stepMapper.updateById(step);
        }
    }

    private List<TaskStepEntity> loadSteps(UUID taskId) {
        return stepMapper.selectList(Wrappers.<TaskStepEntity>lambdaQuery()
                .eq(TaskStepEntity::getTaskId, taskId)
                .orderByAsc(TaskStepEntity::getSequenceNo));
    }

    private TaskStepEntity findByRole(List<TaskStepEntity> steps, String role) {
        for (TaskStepEntity step : steps) {
            if (role.equals(step.getRole())) {
                return step;
            }
        }
        return null;
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
            ctx.feedback = null;
            ctx.retryOf = null;
            return routeState(state, "next");
        }
        markStepRunning(task, step);
        TaskRunEntity run = taskRunService.createForStep(task.getProjectId(), task.getId(), step.getId(),
                step.getRole(), step.getAssignedAgentId(), task.getCreatedBy(), ctx.retryOf);
        taskRunService.markRunning(run.getId());
        AgentInput input = contextAssembler.assemble(task, step, phase, ctx.feedback, run.getId(), ctx.planResult,
                ctx.codingResult, ctx.testResult, ctx.groupContext);
        AgentRunOutcome outcome = safeExecute(agent.get(), phase, input);
        ctx.lastRunId = run.getId();
        if (phase == OrchestrationPhase.CODING && outcome.getOutcome() == RunOutcome.SUCCEEDED) {
            ctx.lastCodingRunId = run.getId();
        }
        if (phase == OrchestrationPhase.PLAN && outcome.getPlanResult() != null) {
            ctx.planResult = outcome.getPlanResult();
        } else if (phase == OrchestrationPhase.CODING && outcome.getCodingResult() != null) {
            ctx.codingResult = outcome.getCodingResult();
        } else if (phase == OrchestrationPhase.TESTING && outcome.getTestResult() != null) {
            ctx.testResult = outcome.getTestResult();
        }
        // AGENTS.md：Run 产物必须先成功落库，再发布 Run 终态事件（type 用 step.role，修复 PLAN 的 role 为 null）
        artifactService.createRunArtifact(task, run, step, step.getRole(), runArtifactSummary(step, outcome));
        taskRunService.complete(run.getId(), terminalStatus(outcome.getOutcome()));
        markStepSettled(task, step, outcome.getOutcome());
        StateMachineDecision decision = stateMachine.decide(phase, outcome.getOutcome(), ctx.counters);
        String route;
        switch (decision.getAction()) {
            case ADVANCE -> {
                if (phase == OrchestrationPhase.PLAN && outcome.getPlanResult() != null) {
                    backfillPlanSteps(task, outcome.getPlanResult());
                    updateTaskStatus(task, "RUNNING");
                }
                ctx.feedback = null;
                ctx.retryOf = null;
                route = "next";
            }
            case REQUEUE_CODING -> {
                ctx.feedback = outcome;
                ctx.retryOf = ctx.lastRunId;
                route = "requeue";
            }
            case RETRY_PHASE -> {
                ctx.feedback = null;
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

    /**
     * PLANNER 成功后回填模板步骤指令并落 plan 产物：仅覆盖仍为模板通用指令的
     * DEVELOPER/TESTER 步骤（用户自写的指令不覆盖），TESTER 回填计划中的测试计划。
     */
    private void backfillPlanSteps(TaskEntity task, PlanResult plan) {
        for (TaskStepEntity step : loadSteps(task.getId())) {
            if ("DEVELOPER".equals(step.getRole()) && TEMPLATE_DEVELOPER_INSTRUCTION.equals(step.getInstruction())) {
                step.setInstruction(planInstruction(plan));
                step.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                stepMapper.updateById(step);
            } else if ("TESTER".equals(step.getRole()) && TEMPLATE_TESTER_INSTRUCTION.equals(step.getInstruction())) {
                if (plan.getTestPlan() != null && !plan.getTestPlan().isBlank()) {
                    step.setInstruction(plan.getTestPlan());
                    step.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                    stepMapper.updateById(step);
                }
            }
        }
        // PLAN 产物只属于 Task，不关联 TaskRun/TaskStep（AGENTS.md）
        artifactService.createPlan(task, planSummary(plan));
    }

    private Map<String, Object> routeState(TaskOrchestrationState state, String route) {
        return Map.of("projectId", state.getProjectId().toString(), "taskId", state.getTaskId().toString(),
                "route", route);
    }

    /**
     * Agent 抛异常统一按基础设施失败处理，避免异常破坏状态机推进。
     */
    private AgentRunOutcome safeExecute(Agent agent, OrchestrationPhase phase, AgentInput input) {
        try {
            return agent.run(input);
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

    /**
     * Run 级执行产物的脱敏摘要：角色、终态、Agent 反馈消息与每轮 LLM 观测。观测经
     * {@link LlmObservation#toSummary()} 序列化为脱敏 Map（仅 phase/round/字符数/结束原因/
     * 工具名/错误码/sha256），路径与敏感键由服务端 sanitize 兜底。
     */
    private Map<String, Object> runArtifactSummary(TaskStepEntity step, AgentRunOutcome outcome) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("role", step.getRole());
        summary.put("status", terminalStatus(outcome.getOutcome()));
        summary.put("message", outcome.getMessage());
        if (outcome.getObservations() != null && !outcome.getObservations().isEmpty()) {
            List<Map<String, Object>> observations = outcome.getObservations().stream()
                    .map(LlmObservation::toSummary)
                    .toList();
            summary.put("observations", observations);
        }
        return summary;
    }

    /**
     * 方案 A + 优先级：按角色在团队内解析 ACTIVE Agent——创建者本人的 PRIVATE Agent 优先于
     * TEAM Agent（体现「个人变体覆盖团队默认」），同组按名称升序取第一个；查不到返回 null，
     * 该步骤内置兜底或跳过（不使任务失败）。与 TaskService.validateAgent 的授权条件一致。
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
                                .eq(AgentEntity::getCreatedBy, task.getCreatedBy()))));
        return candidates.stream()
                .sorted(Comparator.comparingInt((AgentEntity agent) -> "PRIVATE".equals(agent.getVisibility()) ? 0 : 1)
                        .thenComparing(AgentEntity::getName, Comparator.nullsLast(String::compareTo)))
                .map(AgentEntity::getId)
                .findFirst()
                .orElse(null);
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

    private List<UUID> repositoryIds(TaskEntity task) {
        return workspaceRepositoryMapper.selectByWorkspace(task.getWorkspaceId()).stream()
                .map(WorkspaceRepositoryEntity::getProjectRepositoryId).toList();
    }

    /**
     * PLAN 产物的用户可见摘要：目标与实现步骤标题，不落完整指令与文件路径明细。
     */
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

    private String developerNodeId(List<TaskStepEntity> steps) {
        for (TaskStepEntity step : steps) {
            if ("DEVELOPER".equals(step.getRole())) {
                return step.getId().toString();
            }
        }
        return steps.get(0).getId().toString();
    }

    private void markStepRunning(TaskEntity task, TaskStepEntity step) {
        step.setStatus("RUNNING");
        step.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        stepMapper.updateById(step);
        publishStepUpdated(task, step);
    }

    private void markStepSkipped(TaskEntity task, TaskStepEntity step) {
        step.setStatus("SKIPPED");
        step.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        stepMapper.updateById(step);
        publishStepUpdated(task, step);
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
        sendAgentCard(task, "task-" + task.getId(), status, null, taskResultMessage(status));
    }

    /**
     * 成功终态：生成待用户确认的 Diff 批次。无未提交改动（FINAL_DIFF_EMPTY）视为业务上的成功
     * 降级为 SUCCEEDED 并发布 diff-review.skipped 事件作为依据；其余失败（内部一致性、快照无效、
     * Worker 不可用等）落 FAILED，不伪装成成功（后端3 决策：按异常类型区分，不统一降级）。
     */
    private String completeWithDiffBatch(TaskEntity task, TaskExecutionContext ctx) {
        try {
            finalDiffBundles.createPendingBatch(task.getProjectId(), task.getId(), ctx.lastCodingRunId);
            return WAITING_DIFF_CONFIRMATION;
        } catch (ApiException e) {
            if ("FINAL_DIFF_EMPTY".equals(e.code())) {
                log.warn("final diff empty, task finishes SUCCEEDED, taskId={}: {}", task.getId(), e.getMessage());
                publishDiffReviewSkipped(task, e.code());
                return "SUCCEEDED";
            }
            log.warn("final diff batch creation failed, task finishes FAILED, taskId={}, code={}: {}",
                    task.getId(), e.code(), e.getMessage());
            return "FAILED";
        } catch (RuntimeException e) {
            log.error("final diff batch creation failed, task finishes FAILED, taskId={}: {}",
                    task.getId(), e.getMessage(), e);
            return "FAILED";
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
    private void sendAgentCard(TaskEntity task, String idSuffix, String status, String node, String message) {
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
        UUID cardSenderId = orchestratorAgents.resolveIdForTask(task);
        try {
            if (cardSenderId != null) {
                messageService.sendAsAgent(task.getRequirementGroupId(), cardSenderId, body);
            } else {
                log.warn("orchestrator agent missing, card degrades to SYSTEM, taskId={}, suffix={}",
                        task.getId(), idSuffix);
                messageService.sendAsSystem(task.getRequirementGroupId(), body);
            }
        } catch (RuntimeException e) {
            log.warn("agent card skipped, taskId={}, suffix={}: {}", task.getId(), idSuffix, e.getMessage());
        }
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
    private String taskResultMessage(String status) {
        return switch (status) {
            case WAITING_DIFF_CONFIRMATION -> "任务开发完成，等待你对 Diff 的确认";
            case "SUCCEEDED" -> "任务已完成";
            case "FAILED" -> "任务执行失败";
            case "CANCELLED" -> "任务已取消";
            default -> "任务状态更新：" + status;
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

    private void requireStartable(TaskEntity task) {
        if (!STARTABLE_TASK_STATUSES.contains(task.getStatus())) {
            throw new IllegalStateException("Task " + task.getId() + " is not startable from status " + task.getStatus());
        }
    }

    /**
     * 一次 orchestrate 会话内的执行现场：跨节点传递的富结果、循环反馈、最近 TaskRun、
     * 有序步骤与循环计数。与图状态解耦，仅在进程内按 taskId 暂存，invoke 结束后由 orchestrate 清理。
     */
    private static final class TaskExecutionContext {
        private final TaskEntity task;
        private final OrchestrationCounters counters = new OrchestrationCounters();
        private List<TaskStepEntity> steps;
        private AgentRunOutcome feedback;
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
    }
}
