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
     * 任务创建提交后，异步启动编排；失败只记录日志，避免重复/并发执行与线程泄漏。
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTaskCreated(TaskCreatedEvent event) {
        try {
            orchestrator.orchestrate(event.projectId(), event.taskId());
        } catch (RuntimeException e) {
            log.warn("task orchestration trigger skipped for task {}: {}", event.taskId(), e.getMessage());
        }
    }
}
