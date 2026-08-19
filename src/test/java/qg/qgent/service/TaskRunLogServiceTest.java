package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import qg.qgent.entity.TaskEntity;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.ExecutionLogEntity;
import qg.qgent.mapper.ExecutionLogMapper;
import qg.qgent.mapper.TaskMapper;

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
}
