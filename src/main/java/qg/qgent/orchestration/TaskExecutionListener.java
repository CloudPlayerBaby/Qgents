package qg.qgent.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import qg.qgent.service.TaskCreatedEvent;

/**
 * 编排触发入口：任务创建事务提交后，异步驱动 {@link TaskOrchestrator} 执行。
 * <p>
 * 两个约束必须同时满足：
 * <ul>
 *   <li>{@link TransactionalEventListener}(AFTER_COMMIT)：任务及其 Workspace/仓库已落库
 *       提交后才触发，避免读到未提交数据或与创建事务争用；</li>
 *   <li>{@link Async}：{@code orchestrate} 是同步长任务（真实 LLM + Worker HTTP，可能数分钟），
 *       不能占用建任务的 HTTP 请求线程。</li>
 * </ul>
 * 幂等兜底：重复触发时 {@code orchestrate} 的 requireStartable 校验会拒绝非
 * PLANNING/PENDING/RUNNING 的任务，本监听器吞掉该异常并记录日志，不重复执行。
 */
@Component
public class TaskExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(TaskExecutionListener.class);

    private final TaskOrchestrator orchestrator;

    public TaskExecutionListener(TaskOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 任务创建提交后，异步启动编排。
     * <p>
     * 监听器只吞「不应执行」的护栏异常：重复触发时 {@code orchestrate} 的 requireStartable
     * 幂等拒绝、任务/项目归属不符等。真正的执行失败（Sandbox Worker 不可达、图执行崩溃等）
     * 已在 {@link TaskOrchestrator#orchestrate} 内部落 FAILED 终态并通知用户，不会外抛到
     * 这里；若未来出现新逃逸的异常类别，此处的兜底日志是排查入口，任务状态不因此回滚。
     */
    @Async("taskOrchestratorExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCreated(TaskCreatedEvent event) {
        log.info("task created event received, taskId={} projectId={}", event.taskId(), event.projectId());
        try {
            orchestrator.orchestrate(event.projectId(), event.taskId());
        } catch (RuntimeException e) {
            log.warn("task orchestration trigger rejected for task {}: {}", event.taskId(), e.getMessage(), e);
        }
    }
}
