package qg.qgent.orchestration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;

import qg.qgent.service.TaskResumeRequestedEvent;

/**
 * {@link TaskResumeListener} 单测：重试受理后异步从指定步骤触发续跑（携带 retryOfTaskRunId）；
 * 异常被吞掉不影响受理链路。
 */
class TaskResumeListenerTest {

    @Test
    void usesDedicatedOrchestrationExecutor() throws NoSuchMethodException {
        Async async = TaskResumeListener.class
                .getMethod("onTaskResumeRequested", TaskResumeRequestedEvent.class)
                .getAnnotation(Async.class);

        assertEquals("taskOrchestratorExecutor", async.value());
    }

    @Test
    void onResumeRequestedTriggersOrchestrationFromStepWithRetrySource() {
        TaskOrchestrator orchestrator = mock(TaskOrchestrator.class);
        TaskResumeListener listener = new TaskResumeListener(orchestrator);
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID sourceRunId = UUID.randomUUID();

        listener.onTaskResumeRequested(new TaskResumeRequestedEvent(projectId, taskId, stepId, sourceRunId));

        verify(orchestrator).orchestrate(projectId, taskId, stepId, sourceRunId);
    }

    @Test
    void onResumeRequestedSwallowsOrchestrationFailure() {
        TaskOrchestrator orchestrator = mock(TaskOrchestrator.class);
        doThrow(new IllegalStateException("already claimed")).when(orchestrator).orchestrate(any(), any(), any(), any());
        TaskResumeListener listener = new TaskResumeListener(orchestrator);

        assertDoesNotThrow(() -> listener.onTaskResumeRequested(
                new TaskResumeRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID())));
    }

    @Test
    void onResumeRequestedPassesNullStartStepAndNullRetrySourceForFullRestart() {
        TaskOrchestrator orchestrator = mock(TaskOrchestrator.class);
        TaskResumeListener listener = new TaskResumeListener(orchestrator);
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        listener.onTaskResumeRequested(new TaskResumeRequestedEvent(projectId, taskId, null, null));

        verify(orchestrator).orchestrate(eq(projectId), eq(taskId), eq(null), eq(null));
    }
}
