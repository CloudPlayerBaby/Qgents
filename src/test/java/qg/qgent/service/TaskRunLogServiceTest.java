package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.ExecutionLogEntity;
import qg.qgent.mapper.ExecutionLogMapper;
import qg.qgent.mapper.TaskMapper;
import qg.qgent.orchestration.result.TestResult;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskRunLogServiceTest {
    @Test
    void persistsSanitizedEntryBeforePublishingProgressEvent() {
        ExecutionLogMapper logs = mock(ExecutionLogMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        EventService events = mock(EventService.class);
        TaskRunLogService service = new TaskRunLogService(logs, tasks, events);
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        TaskRunEntity run = new TaskRunEntity();
        run.setId(UUID.randomUUID());
        run.setProjectId(projectId);
        run.setTaskId(taskId);
        TaskEntity task = new TaskEntity();
        task.setId(taskId);
        task.setRequirementGroupId(UUID.randomUUID());
        when(logs.nextSequence(run.getId())).thenReturn(7L);
        when(tasks.selectById(taskId)).thenReturn(task);

        InOrder order = inOrder(logs, events);
        ExecutionLogEntity entry = service.append(run, "terminal", "WORKER", "token=secret C:\\Users\\renpe\\repo");

        assertEquals(8L, entry.getSequenceNo());
        assertEquals("TERMINAL", entry.getEntryType());
        assertTrue(entry.getContent().contains("token=[redacted]"));
        assertTrue(entry.getContent().contains("[host path omitted]"));
        order.verify(logs).insert(entry);
        order.verify(events).publish(eq(projectId), eq(task.getRequirementGroupId()),
                eq("task-run.step.progress"), eq(run.getId().toString()), any(Map.class));
    }

    @Test
    void persistsVerificationCommandSummaryAndFailures() {
        ExecutionLogMapper logs = mock(ExecutionLogMapper.class);
        TaskMapper tasks = mock(TaskMapper.class);
        EventService events = mock(EventService.class);
        TaskRunLogService service = new TaskRunLogService(logs, tasks, events);
        TaskRunEntity run = new TaskRunEntity();
        run.setId(UUID.randomUUID());
        run.setProjectId(UUID.randomUUID());
        run.setTaskId(UUID.randomUUID());
        when(logs.nextSequence(run.getId())).thenReturn(0L, 1L);

        TestResult result = new TestResult();
        result.setSuccess(false);
        result.setVerificationMode("COMMAND");
        result.setCommand("mvn test");
        result.setExitCode(1);
        result.setSummary("1 个测试失败");
        TestResult.Failure failure = new TestResult.Failure();
        failure.setName("LoginTest");
        failure.setSeverity("ERROR");
        failure.setReason("expected 200 but got 500");
        result.setFailures(java.util.List.of(failure));

        service.appendVerificationResult(run, result);

        ArgumentCaptor<ExecutionLogEntity> captured = ArgumentCaptor.forClass(ExecutionLogEntity.class);
        verify(logs, times(2)).insert(captured.capture());
        verify(events, times(2)).publish(eq(run.getProjectId()), isNull(),
                eq("task-run.step.progress"), eq(run.getId().toString()), any(Map.class));
        assertTrue(captured.getAllValues().stream().anyMatch(entry -> "TEST".equals(entry.getNode())
                && entry.getContent().contains("mvn test")
                && entry.getContent().contains("exitCode：1")
                && entry.getContent().contains("失败项数量：1")
                && !entry.getContent().contains("1 个测试失败")));
        assertTrue(captured.getAllValues().stream().anyMatch(entry -> "TEST/FAILURE".equals(entry.getNode())
                && entry.getContent().contains("验证失败项详情已隐藏")
                && !entry.getContent().contains("expected 200 but got 500")));
    }
}
