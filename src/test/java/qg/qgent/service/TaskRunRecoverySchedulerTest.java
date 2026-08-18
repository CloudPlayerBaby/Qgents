package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskStepEntity;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.TaskStepMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 崩溃遗留任务恢复调度器测试：扫描卡死任务、从第一个未完成步骤发布续跑事件、无任务不动作；
 * 以及陈旧活跃 Run（Worker 挂起）的原子回收 → Task FAILED → 置 Step FAILED → 落失败产物，
 * 不再自动续跑旧 Task。
 */
class TaskRunRecoverySchedulerTest {
    private final TaskMapper tasks = mock(TaskMapper.class);
    private final TaskStepMapper steps = mock(TaskStepMapper.class);
    private final TaskRunMapper runMapper = mock(TaskRunMapper.class);
    private final TaskExecutionArtifactService artifacts = mock(TaskExecutionArtifactService.class);
    private final EventService taskEvents = mock(EventService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TaskRunRecoveryScheduler scheduler = new TaskRunRecoveryScheduler(tasks, steps, runMapper,
            artifacts, taskEvents, events, Duration.ofMinutes(20));

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

    @Test
    void resumesPendingTaskAfterWorkspaceWriteLeaseIsReleased() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        task.setStatus("PENDING");
        when(tasks.selectPendingWithAvailableWorkspaceLease(10)).thenReturn(List.of(taskId));
        when(tasks.selectById(taskId)).thenReturn(task);

        scheduler.recover();

        verify(events).publishEvent(org.mockito.ArgumentMatchers.argThat((TaskResumeRequestedEvent event) ->
                event.taskId().equals(taskId) && event.projectId().equals(projectId)
                        && event.startStepId() == null && event.retryOfTaskRunId() == null));
    }

    @Test
    void reclaimsStaleRunAndFailsTaskWithoutPublishingResume() {
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID firstIncomplete = UUID.randomUUID();

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        TaskRunEntity run = new TaskRunEntity();
        run.setId(runId);
        run.setTaskId(taskId);
        run.setTaskStepId(stepId);
        run.setRole("DEVELOPER");
        TaskStepEntity step = new TaskStepEntity();
        step.setId(stepId);
        step.setTaskId(taskId);
        step.setRole("DEVELOPER");

        when(runMapper.selectStaleRuns(any(), anyInt())).thenReturn(List.of(runId));
        when(runMapper.reclaimStaleRun(eq(runId), any())).thenReturn(1);
        when(runMapper.selectById(runId)).thenReturn(run);
        when(tasks.selectById(taskId)).thenReturn(task);
        when(tasks.failAfterStaleRun(projectId, taskId)).thenReturn(1);
        when(steps.selectById(stepId)).thenReturn(step);

        scheduler.recover();

        verify(runMapper).reclaimStaleRun(eq(runId), any());
        ArgumentCaptor<Map> summary = ArgumentCaptor.forClass(Map.class);
        verify(artifacts).createRunArtifact(eq(task), eq(run), eq(step), eq("CODING"), summary.capture());
        assertThat(summary.getValue().get("failureCode")).isEqualTo("ORPHANED_RUN_TIMEOUT");
        verify(taskEvents).publish(eq(projectId), eq(task.getRequirementGroupId()), eq("task.updated"),
                eq(taskId.toString()), org.mockito.ArgumentMatchers.anyMap());
        verify(taskEvents).publish(eq(projectId), eq(task.getRequirementGroupId()), eq("task-run.updated"),
                eq(runId.toString()), org.mockito.ArgumentMatchers.anyMap());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void skipsStaleRunWhenCasMisses() {
        UUID runId = UUID.randomUUID();
        when(runMapper.selectStaleRuns(any(), anyInt())).thenReturn(List.of(runId));
        when(runMapper.reclaimStaleRun(eq(runId), any())).thenReturn(0); // 已被他人回收/已终态

        scheduler.recover();

        verify(runMapper, never()).selectById(runId);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void staleRunIsTerminalEvenWhenNoTasklessOrphans() {
        // 回归：selectStaleRuns 命中但 selectStaleOrphaned 为空时，陈旧 run 仍收敛为 FAILED。
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID firstStep = UUID.randomUUID();

        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setProjectId(projectId);
        TaskRunEntity run = new TaskRunEntity();
        run.setId(runId);
        run.setTaskId(taskId);
        run.setTaskStepId(stepId);
        run.setRole("TESTER");
        TaskStepEntity step = new TaskStepEntity();
        step.setId(stepId);
        step.setTaskId(taskId);
        step.setRole("TESTER");

        when(runMapper.selectStaleRuns(any(), anyInt())).thenReturn(List.of(runId));
        when(runMapper.reclaimStaleRun(eq(runId), any())).thenReturn(1);
        when(runMapper.selectById(runId)).thenReturn(run);
        when(tasks.selectById(taskId)).thenReturn(task);
        when(tasks.failAfterStaleRun(projectId, taskId)).thenReturn(1);
        when(steps.selectById(stepId)).thenReturn(step);
        when(tasks.selectStaleOrphaned(any(), anyInt())).thenReturn(List.of());

        scheduler.recover();

        ArgumentCaptor<Map> summary = ArgumentCaptor.forClass(Map.class);
        verify(artifacts).createRunArtifact(eq(task), eq(run), eq(step), eq("TESTING"), summary.capture());
        assertThat(summary.getValue().get("failureCode")).isEqualTo("ORPHANED_RUN_TIMEOUT");
        verify(taskEvents).publish(eq(projectId), eq(task.getRequirementGroupId()), eq("task.updated"),
                eq(taskId.toString()), org.mockito.ArgumentMatchers.anyMap());
        verify(events, never()).publishEvent(any());
    }
}
