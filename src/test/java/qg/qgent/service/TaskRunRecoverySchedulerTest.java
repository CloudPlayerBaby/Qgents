package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import qg.qgent.entity.TaskEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskStepMapper;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 崩溃遗留任务恢复调度器测试：扫描卡死任务、从第一个未完成步骤发布续跑事件、无任务不动作。
 */
class TaskRunRecoverySchedulerTest {
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final TaskStepMapper steps = mock(TaskStepMapper.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TaskRunRecoveryScheduler scheduler = new TaskRunRecoveryScheduler(tasks, steps, events);

    @Test
    void recoversOrphanedTaskFromFirstIncompleteStep() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        when(tasks.selectStaleOrphaned(any(), anyInt())).thenReturn(List.of(taskId));
        when(tasks.selectById(taskId)).thenReturn(task);
        when(steps.selectFirstIncompleteStep(taskId)).thenReturn(stepId);

        scheduler.recover();

        verify(events).publishEvent(org.mockito.ArgumentMatchers.argThat((TaskResumeRequestedEvent e) ->
                e.taskId().equals(taskId) && e.projectId().equals(projectId) && e.startStepId().equals(stepId)
                        && e.retryOfTaskRunId() == null));
    }

    @Test
    void recoversOrphanedTaskFromFirstStepWhenAllComplete() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        when(tasks.selectStaleOrphaned(any(), anyInt())).thenReturn(List.of(taskId));
        when(tasks.selectById(taskId)).thenReturn(task);
        when(steps.selectFirstIncompleteStep(taskId)).thenReturn(null);
        when(steps.selectFirstStep(taskId)).thenReturn(stepId);

        scheduler.recover();

        verify(events).publishEvent(org.mockito.ArgumentMatchers.argThat((TaskResumeRequestedEvent e) ->
                e.startStepId().equals(stepId) && e.retryOfTaskRunId() == null));
    }

    @Test
    void skipsMissingTask() {
        UUID taskId = UUID.randomUUID();
        when(tasks.selectStaleOrphaned(any(), anyInt())).thenReturn(List.of(taskId));
        when(tasks.selectById(taskId)).thenReturn(null);

        scheduler.recover();

        verify(events, never()).publishEvent(any());
    }

    @Test
    void noOrphansNoEvent() {
        when(tasks.selectStaleOrphaned(any(), anyInt())).thenReturn(List.of());

        scheduler.recover();

        verify(events, never()).publishEvent(any());
    }
}
