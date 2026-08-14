package qg.qgent.orchestration;

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
import qg.qgent.service.TaskExecutionArtifactService;
import qg.qgent.service.FinalDiffBundleService;
import qg.qgent.service.TaskRunService;
import qg.qgent.service.TaskService;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 确定性任务编排器：驱动 Task 从 PLAN 到 SUCCESS/FAILED/CANCELLED 的完整链路。
 * 不调用 LLM，只负责状态判断、Agent 调度、TaskRun 生命周期、结果路由、
 * Test/Review 失败后的 Coding 重试、循环上限与基础设施重试。
 *
 * Phase 1 约束（方案 B）：PLAN 相位不创建 TaskRun（task_runs.task_step_id NOT NULL，
 * 计划产出前没有步骤可挂），由本类内联执行 Plan Agent 并把 PlanResult 经 TaskService.addSteps
 * 落为 DEVELOPER/TESTER/REVIEWER 三个步骤；CODING/TESTING/REVIEWING 各创建真实 TaskRun。
 */
@Service
public class TaskOrchestrator {
    private static final Set<String> STARTABLE_TASK_STATUSES = Set.of("PLANNING", "PENDING", "RUNNING");

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

    public TaskOrchestrator(OrchestrationStateMachine stateMachine, StepScheduler stepScheduler,
            AgentRunExecutor agentRunExecutor, AgentContextAssembler contextAssembler, TaskService taskService,
            TaskRunService taskRunService, TaskMapper taskMapper, TaskStepMapper stepMapper,
            WorkspaceRepositoryMapper workspaceRepositoryMapper, EventService eventService,
            NotificationService notificationService, SandboxSessionManager sandboxSessionManager,
            TaskExecutionArtifactService artifactService, FinalDiffBundleService finalDiffBundles) {
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
    }

    /**
     * 同步驱动一个 Task 的完整编排链路。Mock Agent 下整条链路同步跑完；
     * 接入真实异步执行后，本方法将退化为"推进单步"并由运行完成事件驱动。
     * <p>
     * 入口为整条链路准备一次 Sandbox 会话（Worker 端口启用时），并在终态后释放；
     * 未启用 Worker 时会话管理器为 no-op，不影响本地端口链路。
     */
    public void orchestrate(UUID projectId, UUID taskId) {
        TaskEntity task = requireTask(projectId, taskId);
        requireStartable(task);
        try {
            sandboxSessionManager.acquire(task.getId(), task.getProjectId(), task.getWorkspaceId());
            runLoop(task);
        } finally {
            sandboxSessionManager.release(task.getWorkspaceId());
        }
    }

    /** 推进 PLAN→CODING→TESTING→REVIEWING 循环，直到进入终态。 */
    private void runLoop(TaskEntity task) {
        OrchestrationCounters counters = new OrchestrationCounters();
        OrchestrationPhase phase = OrchestrationPhase.PLAN;
        AgentRunOutcome feedback = null;
        UUID retryOf = null;
        UUID lastRunId = null;
        UUID lastCodingRunId = null;
        PlanResult planResult = null;
        CodingResult codingResult = null;
        TestResult testResult = null;

        while (true) {
            PhaseRun result = runPhase(task, phase, feedback, retryOf, planResult, codingResult, testResult);
            if (result.runId != null) {
                lastRunId = result.runId;
            }
            if (phase == OrchestrationPhase.PLAN && result.outcome.getPlanResult() != null) {
                planResult = result.outcome.getPlanResult();
            }
            if (phase == OrchestrationPhase.CODING) {
                if (result.outcome.getCodingResult() != null) {
                    codingResult = result.outcome.getCodingResult();
                }
                lastCodingRunId = result.runId;
            }
            if (phase == OrchestrationPhase.TESTING && result.outcome.getTestResult() != null) {
                testResult = result.outcome.getTestResult();
            }
            StateMachineDecision decision = stateMachine.decide(phase, result.outcome.getOutcome(), counters);
            if (phase == OrchestrationPhase.REVIEWING && decision.getAction() == StateMachineDecision.Action.COMPLETE_SUCCESS) {
                awaitFinalDiffConfirmation(task, lastCodingRunId);
                return;
            }
            switch (decision.getAction()) {
                case ADVANCE -> {
                    if (phase == OrchestrationPhase.PLAN) {
                        persistPlanSteps(task, result.outcome.getPlanResult());
                        updateTaskStatus(task, "RUNNING");
                    }
                    phase = decision.getNextPhase();
                    feedback = null;
                    retryOf = null;
                }
                case REQUEUE_CODING -> {
                    phase = decision.getNextPhase();
                    feedback = result.outcome;
                    retryOf = lastRunId;
                }
                case RETRY_PHASE -> {
                    phase = decision.getNextPhase();
                    feedback = null;
                    retryOf = lastRunId;
                }
                default -> {
                    finishTask(task, decision.getAction());
                    return;
                }
            }
        }
    }

    /** 执行一个相位：PLAN 内联执行（无 TaskRun），其余相位创建 TaskRun 并推进。 */
    private PhaseRun runPhase(TaskEntity task, OrchestrationPhase phase, AgentRunOutcome feedback, UUID retryOf,
            PlanResult planResult, CodingResult codingResult, TestResult testResult) {
        if (phase == OrchestrationPhase.PLAN) {
            AgentInput input = contextAssembler.assemblePlan(task);
            AgentRunOutcome outcome = safeExecute(phase, input);
            artifactService.createPlan(task, artifactSummary(phase, outcome));
            return new PhaseRun(outcome, null);
        }
        TaskStepEntity step = stepScheduler.findStepForPhase(task.getId(), phase);
        markStepRunning(task, step);
        TaskRunEntity run = taskRunService.createForStep(task.getProjectId(), task.getId(), step.getId(),
                phase.role(), null, task.getCreatedBy(), retryOf);
        taskRunService.markRunning(run.getId());
        AgentInput input = contextAssembler.assemble(task, step, phase, feedback, run.getId(), planResult,
                codingResult, testResult);
        AgentRunOutcome outcome = safeExecute(phase, input);
        artifactService.createRunArtifact(task, run, step, phase.name(), artifactSummary(phase, outcome));
        taskRunService.complete(run.getId(), terminalStatus(outcome.getOutcome()));
        markStepSettled(task, step, outcome.getOutcome());
        return new PhaseRun(outcome, run.getId());
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

    /** Produce a compact, durable card without exposing raw prompts, secrets, or command output. */
    private Map<String, Object> artifactSummary(OrchestrationPhase phase, AgentRunOutcome outcome) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("phase", phase.name());
        summary.put("outcome", outcome.getOutcome().name());
        switch (phase) {
            case PLAN -> {
                PlanResult plan = outcome.getPlanResult();
                if (plan != null) {
                    summary.put("taskUnderstanding", plan.getTaskUnderstanding());
                    summary.put("objectives", plan.getObjectives());
                    summary.put("implementationSteps", plan.getImplementationSteps().stream()
                            .map(step -> artifactMap("title", step.getTitle(), "files", step.getFiles(),
                                    "description", step.getDescription())).toList());
                    summary.put("testPlan", plan.getTestPlan());
                    summary.put("risks", plan.getRisks());
                }
            }
            case CODING -> {
                CodingResult coding = outcome.getCodingResult();
                if (coding != null) {
                    summary.put("modifiedFiles", coding.getModifiedFiles());
                    summary.put("summary", coding.getSummary());
                    summary.put("changes", coding.getChanges());
                    summary.put("selfChecks", coding.getSelfChecks().stream()
                            .map(check -> artifactMap("exitCode", check.getExitCode(), "ok", check.isOk())).toList());
                    summary.put("errors", coding.getErrors());
                }
            }
            case TESTING -> {
                TestResult testing = outcome.getTestResult();
                if (testing != null) {
                    summary.put("exitCode", testing.getExitCode());
                    summary.put("summary", testing.getSummary());
                    summary.put("needsCodingFix", testing.isNeedsCodingFix());
                    summary.put("failures", testing.getFailures().stream()
                            .map(failure -> artifactMap("name", failure.getName(), "reason", failure.getReason(),
                                    "severity", failure.getSeverity())).toList());
                }
            }
            case REVIEWING -> {
                var review = outcome.getReviewResult();
                if (review != null) {
                    summary.put("summary", review.getSummary());
                    summary.put("needsCodingFix", review.isNeedsCodingFix());
                    summary.put("suggestions", review.getSuggestions());
                    summary.put("findings", review.getFindings().stream()
                            .map(finding -> artifactMap("severity", finding.getSeverity(), "file", finding.getFile(),
                                    "line", finding.getLine(), "issue", finding.getIssue(),
                                    "suggestion", finding.getSuggestion())).toList());
                }
            }
        }
        return summary;
    }

    private Map<String, Object> artifactMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            if (values[index + 1] != null) {
                result.put((String) values[index], values[index + 1]);
            }
        }
        return result;
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

    /** Review approval produces a Task-level Diff; actual Git delivery remains user-confirmed. */
    private void awaitFinalDiffConfirmation(TaskEntity task, UUID finalCodingRunId) {
        if (finalCodingRunId == null) {
            finishTask(task, StateMachineDecision.Action.COMPLETE_FAILED);
            return;
        }
        try {
            UUID batchId = finalDiffBundles.createPendingBatch(task.getProjectId(), task.getId(), finalCodingRunId);
            updateTaskStatus(task, "WAITING_DIFF_CONFIRMATION");
            eventService.publish(task.getProjectId(), task.getRequirementGroupId(), "task.awaiting-diff-confirmation",
                    task.getId().toString(), Map.of("projectId", task.getProjectId(), "taskId", task.getId(),
                            "reviewBatchId", batchId));
        } catch (RuntimeException failure) {
            eventService.publish(task.getProjectId(), task.getRequirementGroupId(), "task.diff-review.failed",
                    task.getId().toString(), Map.of("projectId", task.getProjectId(), "taskId", task.getId(),
                            "reason", failure.getMessage() == null ? "Final Diff snapshot creation failed" : failure.getMessage()));
            finishTask(task, StateMachineDecision.Action.COMPLETE_FAILED);
        }
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
}
