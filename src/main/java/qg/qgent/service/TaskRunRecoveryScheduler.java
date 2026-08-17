package qg.qgent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskStepMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 崩溃遗留任务恢复调度器：周期扫描疑似中断的编排任务（状态 PLANNING/PENDING/RUNNING 且超过
 * 阈值无更新、且没有进行中的 TaskRun），经原子认领后从第一个未完成步骤续跑。
 * <p>
 * 与 TestRun/DryRun 的恢复机制对齐，补齐任务级编排"进程重启后无人接管"的缺口：
 * 编排是同步长任务，进程崩溃后任务会卡在 PLANNING/RUNNING，本调度器负责拾起重跑。
 */
@Service
public class TaskRunRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(TaskRunRecoveryScheduler.class);

    private final TaskMapper tasks;
    private final TaskStepMapper steps;
    private final ApplicationEventPublisher eventPublisher;

    public TaskRunRecoveryScheduler(TaskMapper tasks, TaskStepMapper steps,
                                    ApplicationEventPublisher eventPublisher) {
        this.tasks = tasks;
        this.steps = steps;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 扫描并续跑卡死任务；认领由编排入口 {@code orchestrate(projectId, taskId, startStepId)} 内的
     * claimForResume 原子完成（无进行中 TaskRun 才认领成功），多实例并发安全。
     * 本调度器只负责发现卡死任务并发布续跑事件（AFTER_COMMIT + @Async 执行）。
     */
    @Scheduled(fixedDelayString = "${qgents.task-recovery.poll-delay-ms:30000}")
    public void recover() {
        LocalDateTime staleBefore = LocalDateTime.now(ZoneOffset.UTC).minus(STALE_THRESHOLD);
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

    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(15);
}
