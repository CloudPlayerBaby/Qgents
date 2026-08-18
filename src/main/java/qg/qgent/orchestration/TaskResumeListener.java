package qg.qgent.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import qg.qgent.service.TaskResumeRequestedEvent;

/**
 * 任务续跑触发入口：重试受理事务提交后，异步从指定步骤驱动 {@link TaskOrchestrator} 续跑。
 * <p>
 * 与 {@link TaskExecutionListener} 同一执行器（taskOrchestratorExecutor），避免并发编排同一任务；
 * 续跑仍走 {@code orchestrate(projectId, taskId, startStepId, retryOfTaskRunId)} 的原子认领
 * （claimForResume），重复触发/正在执行的任务会被认领拒绝，日志 warn 不重复执行。
 */
@Component
public class TaskResumeListener {
    private static final Logger log = LoggerFactory.getLogger(TaskResumeListener.class);

    private final TaskOrchestrator orchestrator;

    public TaskResumeListener(TaskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 重试受理提交后，异步从指定步骤续跑；失败只记录日志，避免重复/并发执行与线程泄漏。
     */
    @Async("taskOrchestratorExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTaskResumeRequested(TaskResumeRequestedEvent event) {
        log.info("task resume event received, taskId={} projectId={} startStepId={} retryOfTaskRunId={}",
                event.taskId(), event.projectId(), event.startStepId(), event.retryOfTaskRunId());
        try {
            orchestrator.orchestrate(event.projectId(), event.taskId(), event.startStepId(), event.retryOfTaskRunId());
        } catch (RuntimeException e) {
            log.warn("task resume trigger skipped for task {}: {}", event.taskId(), e.getMessage(), e);
        }
    }
}
