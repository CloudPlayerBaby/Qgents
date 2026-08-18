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

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 崩溃遗留任务恢复调度器：周期扫描疑似中断的编排任务与陈旧活跃 Run，经原子认领后续跑。
 * <p>
 * 两类恢复：
 * <ol>
 *   <li>无进行中 TaskRun 的崩溃任务（进程重启后卡 PLANNING/PENDING/RUNNING）：见 {@code selectStaleOrphaned}；
 *   <li>长期 QUEUED/RUNNING 的陈旧 Run（Worker HTTP 挂起导致 {@code agent.run()} 永不返回）：先原子回收
 *        （reclaimStaleRun CAS 置 FAILED），标其 Step FAILED、落不可变失败产物，再发布续跑事件。
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
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 无进行中 Run 的崩溃任务的陈旧阈值；可配置，默认 15 分钟。
     */
    private final Duration staleRunThreshold;

    public TaskRunRecoveryScheduler(TaskMapper tasks, TaskStepMapper steps, TaskRunMapper runMapper,
                                    TaskExecutionArtifactService artifactService,
                                    ApplicationEventPublisher eventPublisher,
                                    @Value("${qgents.task-recovery.stale-run-threshold:20m}") Duration staleRunThreshold) {
        this.tasks = tasks;
        this.steps = steps;
        this.runMapper = runMapper;
        this.artifactService = artifactService;
        this.eventPublisher = eventPublisher;
        this.staleRunThreshold = staleRunThreshold;
    }

    /**
     * 扫描并续跑卡死任务；认领由编排入口 {@code orchestrate(projectId, taskId, startStepId)} 内的
     * claimForResume 原子完成（无进行中 TaskRun 才认领成功），多实例并发安全。
     * 本调度器只负责发现卡死任务并发布续跑事件（AFTER_COMMIT + @Async 执行）。
     */
    @Scheduled(fixedDelayString = "${qgents.task-recovery.poll-delay-ms:30000}")
    public void recover() {
        reclaimStaleRuns();
        recoverTasklessOrphans();
        recoverPendingWorkspaceLeaseWaiters();
    }

    /**
     * 回收长期处于 QUEUED/RUNNING 的陈旧 Run（Worker 挂起场景）：CAS 置 FAILED 抢到者才继续——
     * 标其 Step FAILED、落不可变失败产物 ORPHANED_RUN_TIMEOUT，再发布续跑事件让状态机重试或终止。
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
                TaskStepEntity step = steps.selectById(run.getTaskStepId());
                markStepFailedIfActive(run.getTaskId(), run.getTaskStepId());
                if (step != null) {
                    artifactService.createRunArtifact(task, run, step,
                            artifactTypeForRole(run.getRole()), orphanSummary(run, step));
                }
                UUID startStepId = steps.selectFirstIncompleteStep(run.getTaskId());
                if (startStepId == null) {
                    startStepId = steps.selectFirstStep(run.getTaskId());
                }
                log.info("reclaimed stale run runId={} taskId={} projectId={} stepId={} startStepId={}",
                        runId, run.getTaskId(), task.getProjectId(), run.getTaskStepId(), startStepId);
                eventPublisher.publishEvent(new TaskResumeRequestedEvent(task.getProjectId(), task.getId(),
                        startStepId, null));
            } catch (RuntimeException e) {
                log.warn("stale run reclaim skipped runId={}: {}", runId, e.getMessage());
            }
        }
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
            log.info("workspace lease available, resuming pending task taskId={} projectId={}",
                    task.getId(), task.getProjectId());
            eventPublisher.publishEvent(new TaskResumeRequestedEvent(task.getProjectId(), task.getId(), null, null));
        }
    }

    private static final Duration ORPHAN_TASK_THRESHOLD = Duration.ofMinutes(15);
}
