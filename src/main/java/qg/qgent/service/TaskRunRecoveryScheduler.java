package qg.qgent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.TaskStepMapper;
import qg.qgent.orchestration.AgentRunOutcome;
import qg.qgent.orchestration.OrchestrationPhase;
import qg.qgent.orchestration.RunOutcome;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 崩溃遗留任务恢复调度器：周期扫描疑似中断的编排任务与陈旧活跃 Run，经原子认领后续跑。
 * <p>
 * 两类恢复：
 * <ol>
 *   <li>无进行中 TaskRun 的崩溃任务（进程重启后卡 PLANNING/PENDING/RUNNING）：见 {@code selectStaleOrphaned}；
 *   <li>长期 QUEUED/RUNNING 的陈旧 Run（Worker HTTP 挂起导致 {@code agent.run()} 永不返回）：先原子回收
 *        （reclaimStaleRun CAS 置 FAILED），将所属 Task 收敛为 FAILED、标其 Step FAILED 并落不可变失败产物；
 *        不自动续跑，避免用户已经看到失败后旧 Task 被后台再次执行。
 * </ol>
 * 多实例并发安全：所有认领都是数据库 CAS（claimForResume / reclaimStaleRun）。
 */
@Service
public class TaskRunRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(TaskRunRecoveryScheduler.class);
    private static final int STALE_RUN_SCAN_LIMIT = 20;

    private final TaskMapper tasks;
    private final TaskStepMapper steps;
    private final TaskRunMapper runMapper;
    private final TaskExecutionArtifactService artifactService;
    private final EventService eventService;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskRunLogService taskRunLogService;
    private final TaskRunFailureDiagnosticService failureDiagnostics;

    /**
     * 无进行中 Run 的崩溃任务的陈旧阈值；可配置，默认 15 分钟。
     */
    private final Duration staleRunThreshold;

    /**
     * 同一 Task 相邻两次续跑事件的最短间隔：编排池（core=2）被长任务占满时，
     * 恢复事件会排队；若每次扫描都对同一卡死任务重复发布，队列会堆积多个
     * 同任务事件，编排认领（claimForResume）只放行一次，其余事件白排队，
     * 拖长任务停留在 PENDING 的时间。进程内去重保证同一任务在窗口内只发一次。
     */
    private static final Duration RESUME_EVENT_DEDUP_WINDOW = Duration.ofMinutes(2);
    private final Map<UUID, Instant> lastResumeEventAt = new ConcurrentHashMap<>();

    public TaskRunRecoveryScheduler(TaskMapper tasks, TaskStepMapper steps, TaskRunMapper runMapper,
                                    TaskExecutionArtifactService artifactService,
                                    EventService eventService,
                                    ApplicationEventPublisher eventPublisher,
                                    @Value("${qgents.task-recovery.stale-run-threshold:20m}") Duration staleRunThreshold) {
        this(tasks, steps, runMapper, artifactService, eventService, eventPublisher, staleRunThreshold, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskRunRecoveryScheduler(TaskMapper tasks, TaskStepMapper steps, TaskRunMapper runMapper,
                                    TaskExecutionArtifactService artifactService,
                                    EventService eventService,
                                     ApplicationEventPublisher eventPublisher,
                                     @Value("${qgents.task-recovery.stale-run-threshold:20m}") Duration staleRunThreshold,
                                     TaskRunLogService taskRunLogService,
                                     TaskRunFailureDiagnosticService failureDiagnostics) {
        this.tasks = tasks;
        this.steps = steps;
        this.runMapper = runMapper;
        this.artifactService = artifactService;
        this.eventService = eventService;
        this.eventPublisher = eventPublisher;
        this.staleRunThreshold = staleRunThreshold;
        this.taskRunLogService = taskRunLogService;
        this.failureDiagnostics = failureDiagnostics;
    }

    /**
     * 扫描崩溃遗留任务。只有没有任何 Run 的孤儿任务会自动发布续跑事件；陈旧活跃 Run
     * 被收敛为 FAILED，等待用户显式创建续作 Task。
     */
    @Scheduled(fixedDelayString = "${qgents.task-recovery.poll-delay-ms:30000}")
    public void recover() {
        reclaimStaleRuns();
        recoverTasklessOrphans();
        recoverPendingWorkspaceLeaseWaiters();
    }

    /**
     * 回收长期处于 QUEUED/RUNNING 的陈旧 Run（Worker 挂起场景）：CAS 置 FAILED 抢到者才继续。
     * 陈旧 Run 表示本次执行已经失败；任务收敛为 FAILED，不再自动续跑旧 Task。
     */
    private void reclaimStaleRuns() {
        LocalDateTime staleBefore = LocalDateTime.now(ZoneOffset.UTC).minus(staleRunThreshold);
        List<UUID> staleRuns = runMapper.selectStaleRuns(staleBefore, STALE_RUN_SCAN_LIMIT);
        for (UUID runId : staleRuns) {
            try {
                if (runMapper.reclaimStaleRun(runId, staleBefore) != 1) {
                    continue; // 已被他人回收或已进入终态
                }
                TaskRunEntity run = runMapper.selectById(runId);
                if (run == null) {
                    continue;
                }
                TaskEntity task = tasks.selectById(run.getTaskId());
                if (task == null) {
                    continue;
                }
                // CAS 已经使 Run 终态；Task 同步收敛后，必须先持久化诊断/产物，再发布任何终态事件。
                boolean taskFailed = tasks.failAfterStaleRun(task.getProjectId(), task.getId()) == 1;
                if (taskFailed) {
                    task.setStatus("FAILED");
                    run.setStatus("FAILED");
                } else {
                    log.info("reclaimed stale run but task already left executable states runId={} taskId={} status={}",
                            runId, run.getTaskId(), task.getStatus());
                }
                TaskStepEntity step = steps.selectById(run.getTaskStepId());
                if (step != null) {
                    recordOrphanFailure(task, run, step);
                    artifactService.createRunArtifact(task, run, step,
                            artifactTypeForRole(run.getRole()), orphanSummary(run, step));
                }
                markStepFailedIfActive(run.getTaskId(), run.getTaskStepId());
                if (taskFailed) {
                    if (taskRunLogService != null) {
                        taskRunLogService.append(run, "TERMINAL", run.getRole(),
                                "ORPHANED_RUN_TIMEOUT：运行超过陈旧阈值未返回，已被恢复器回收");
                    }
                    eventService.publish(task.getProjectId(), task.getRequirementGroupId(), "task.updated",
                            task.getId().toString(), TaskEventPayloads.taskUpdated(task));
                    eventService.publish(task.getProjectId(), task.getRequirementGroupId(), "task-run.updated",
                            run.getId().toString(), TaskEventPayloads.taskRunUpdated(run, 0));
                    log.info("reclaimed stale run and failed task runId={} taskId={} projectId={} stepId={}",
                            runId, run.getTaskId(), task.getProjectId(), run.getTaskStepId());
                }
            } catch (RuntimeException e) {
                log.warn("stale run reclaim skipped runId={}: {}", runId, e.getMessage());
            }
        }
    }

    /** 由恢复器终止的 Run 也必须写入与在线编排一致的失败诊断。 */
    private void recordOrphanFailure(TaskEntity task, TaskRunEntity run, TaskStepEntity step) {
        if (failureDiagnostics == null) {
            return;
        }
        AgentRunOutcome outcome = new AgentRunOutcome();
        outcome.setOutcome(RunOutcome.FAILED_INFRASTRUCTURE);
        outcome.setPhase(orphanPhase(run.getRole()));
        outcome.setFailureCode("AGENT_RUN_TIMEOUT");
        outcome.setDiagnosticFailureCode("ORPHANED_RUN_TIMEOUT");
        outcome.setDiagnosticSource("RECOVERY");
        outcome.setDiagnosticDetail("运行超过陈旧阈值未返回，已被恢复器回收");
        outcome.setMessage("运行超过陈旧阈值未返回，已被恢复器回收");
        failureDiagnostics.record(task, run, step, outcome.getPhase(), outcome);
    }

    private OrchestrationPhase orphanPhase(String role) {
        return switch (role == null ? "" : role) {
            case "PLANNER" -> OrchestrationPhase.PLAN;
            case "TESTER" -> OrchestrationPhase.TESTING;
            case "REVIEWER" -> OrchestrationPhase.REVIEWING;
            default -> OrchestrationPhase.CODING;
        };
    }

    /**
     * 仅当 Step 仍处于 RUNNING/PENDING 时才置 FAILED，避免覆盖已走到后续终态的步骤。
     * 若步骤不存在或已终态则跳过。用字符串列名的普通 UpdateWrapper（非 lambda），
     * 避免在未初始化 MyBatis-Plus 实体元数据的上下文中触发 lambda 缓存解析失败。
     */
    private void markStepFailedIfActive(UUID taskId, UUID stepId) {
        steps.update(null, Wrappers.<TaskStepEntity>update()
                .set("status", "FAILED")
                .eq("id", stepId)
                .eq("task_id", taskId)
                .in("status", "RUNNING", "PENDING"));
    }

    /**
     * 回收 Run 的不可变失败产物摘要：角色、终态、失败码与关联运行/步骤（脱敏，不含命令原文/路径/Token）。
     */
    private Map<String, Object> orphanSummary(TaskRunEntity run, TaskStepEntity step) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("role", run.getRole());
        summary.put("status", "FAILED");
        summary.put("failureCode", "ORPHANED_RUN_TIMEOUT");
        summary.put("message", "运行超过陈旧阈值未返回，已被恢复器回收");
        if (run.getStartedAt() != null) {
            summary.put("startedAt", run.getStartedAt().atOffset(ZoneOffset.UTC).toInstant().toString());
        }
        return summary;
    }

    /**
     * Run 角色 → 稳定产物类型（与编排落库同义），供时间线/前端识别；未知角色回退 RUN。
     */
    private String artifactTypeForRole(String role) {
        return switch (role == null ? "" : role) {
            case "DEVELOPER" -> "CODING";
            case "TESTER" -> "TESTING";
            case "REVIEWER" -> "REVIEWING";
            case "PLANNER", "PLAN" -> "PLAN";
            default -> "RUN";
        };
    }

    /**
     * 无进行中 Run 的崩溃任务：状态 PLANNING/PENDING/RUNNING 且超过阈值无更新、且没有活跃 Run。
     */
    private void recoverTasklessOrphans() {
        LocalDateTime staleBefore = LocalDateTime.now(ZoneOffset.UTC).minus(ORPHAN_TASK_THRESHOLD);
        List<UUID> orphans = tasks.selectStaleOrphaned(staleBefore, 10);
        for (UUID taskId : orphans) {
            TaskEntity task = tasks.selectById(taskId);
            if (task == null) {
                continue;
            }
            // 从第一个未完成步骤续跑；全部步骤已终态（罕见）则从第一个步骤重新执行。
            UUID startStepId = steps.selectFirstIncompleteStep(taskId);
            if (startStepId == null) {
                startStepId = steps.selectFirstStep(taskId);
            }
            if (!requestResume(task, startStepId)) {
                continue;
            }
            log.info("recover orphaned task taskId={} projectId={} startStepId={}", taskId, task.getProjectId(),
                    startStepId);
            // 崩溃恢复没有源运行：retryOfTaskRunId 为 null（续跑首个 run 不指向任何失败运行）
            eventPublisher.publishEvent(new TaskResumeRequestedEvent(task.getProjectId(), taskId, startStepId, null));
        }
    }

    /**
     * 处理因其他 Task 持有 Workspace 写租约而暂缓的任务。只在租约不存在或已到期且任务没有
     * 活跃 Run 时发布续跑事件；真正的启动仍由编排器再次 CAS 领取租约，因而多实例重复扫描安全。
     */
    private void recoverPendingWorkspaceLeaseWaiters() {
        for (UUID taskId : tasks.selectPendingWithAvailableWorkspaceLease(10)) {
            TaskEntity task = tasks.selectById(taskId);
            if (task == null || !"PENDING".equals(task.getStatus())) {
                continue;
            }
            if (!requestResume(task, null)) {
                continue;
            }
            log.info("workspace lease available, resuming pending task taskId={} projectId={}",
                    task.getId(), task.getProjectId());
            eventPublisher.publishEvent(new TaskResumeRequestedEvent(task.getProjectId(), task.getId(), null, null));
        }
    }

    /**
     * 发布续跑事件前的进程内幂等与状态二次确认：
     * <ul>
     *   <li>同一 Task 在 {@link #RESUME_EVENT_DEDUP_WINDOW} 内已发布过续跑事件（无论是否已消费）
     *       则跳过——编排池满时重复事件只会堆积排队，认领只放行一次；</li>
     *   <li>扫描后任务状态已变化（如已被用户重试 / 另一实例认领 / 已取消）时跳过，
     *       由编排侧 {@code claimForResume} 的 CAS 兜底，这里只做前置过滤减少无效事件。</li>
     * </ul>
     *
     * @return true 表示本次应发布续跑事件
     */
    private boolean requestResume(TaskEntity task, UUID startStepId) {
        if (task == null || task.getId() == null) {
            return false;
        }
        if (!isResumableTaskStatus(task.getStatus())) {
            return false;
        }
        Instant now = Instant.now();
        Instant previous = lastResumeEventAt.putIfAbsent(task.getId(), now);
        if (previous != null) {
            if (!previous.plus(RESUME_EVENT_DEDUP_WINDOW).isAfter(now)) {
                lastResumeEventAt.put(task.getId(), now);
            } else {
                log.info("resume event deduped within window taskId={} startStepId={}", task.getId(), startStepId);
                return false;
            }
        }
        return true;
    }

    /**
     * 可恢复的任务状态：仍处于编排启动态（规划/待执行/执行中）。
     * <p>
     * 不含 FAILED：陈旧 Run 被回收后任务收敛为 FAILED 是用户可见的失败终态，
     * 恢复器不自动续跑（避免用户已经看到失败后旧 Task 被后台再次执行）；
     * FAILED 任务只由用户显式重试（{@code TaskRunService.retry}）触发续跑。
     */
    private boolean isResumableTaskStatus(String status) {
        return "PLANNING".equals(status) || "PENDING".equals(status) || "RUNNING".equals(status);
    }

    private static final Duration ORPHAN_TASK_THRESHOLD = Duration.ofMinutes(15);
}
