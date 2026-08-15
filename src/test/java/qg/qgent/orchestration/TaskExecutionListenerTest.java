package qg.qgent.orchestration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;

import qg.qgent.service.TaskCreatedEvent;

/**
 * {@link TaskExecutionListener} 单测：验证任务创建事件会触发编排、编排异常被吞掉不影响创建链路。
 * 直接调用监听器方法，不验证 @Async/@TransactionalEventListener 的代理行为。
 */
class TaskExecutionListenerTest {

    @Test
    void usesDedicatedOrchestrationExecutor() throws NoSuchMethodException {
        Async async = TaskExecutionListener.class
                .getMethod("onTaskCreated", TaskCreatedEvent.class)
                .getAnnotation(Async.class);

        assertEquals("taskOrchestratorExecutor", async.value());
    }

    @Test
    void onTaskCreatedTriggersOrchestration() {
        TaskOrchestrator orchestrator = mock(TaskOrchestrator.class);
        TaskExecutionListener listener = new TaskExecutionListener(orchestrator);
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        listener.onTaskCreated(new TaskCreatedEvent(projectId, taskId));

        verify(orchestrator).orchestrate(projectId, taskId);
    }

    @Test
    void onTaskCreatedSwallowsOrchestrationFailure() {
        TaskOrchestrator orchestrator = mock(TaskOrchestrator.class);
        doThrow(new IllegalStateException("not startable")).when(orchestrator).orchestrate(any(), any());
        TaskExecutionListener listener = new TaskExecutionListener(orchestrator);

        assertDoesNotThrow(() -> listener.onTaskCreated(
                new TaskCreatedEvent(UUID.randomUUID(), UUID.randomUUID())));
    }
}
