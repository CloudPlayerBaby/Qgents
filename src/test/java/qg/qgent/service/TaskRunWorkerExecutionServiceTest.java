package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import qg.qgent.entity.TaskRunEntity;
import qg.qgent.entity.TaskRunWorkerExecutionEntity;
import qg.qgent.mapper.TaskRunMapper;
import qg.qgent.mapper.TaskRunWorkerExecutionMapper;
import qg.qgent.orchestration.worker.WorkerToolExecution;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;

class TaskRunWorkerExecutionServiceTest {

    @Test
    void persistsExecutionIdImmediatelyWithSanitizedFailureSummary() {
        TaskRunWorkerExecutionMapper executions = mock(TaskRunWorkerExecutionMapper.class);
        TaskRunMapper runs = mock(TaskRunMapper.class);
        TaskRunWorkerExecutionService service = new TaskRunWorkerExecutionService(executions, runs);
        UUID runId = UUID.randomUUID();
        TaskRunEntity run = new TaskRunEntity();
        run.setId(runId);
        run.setProjectId(UUID.randomUUID());
        run.setTaskId(UUID.randomUUID());
        when(runs.selectById(runId)).thenReturn(run);

        WorkerToolExecution execution = new WorkerToolExecution();
        execution.setId(UUID.randomUUID());
        execution.setSandboxId(UUID.randomUUID());
        execution.setTool("file.patch");
        execution.setStatus("FAILED");
        execution.setFailureCode("FILE_PATCH_FAILED");
        execution.setFailureReason("token=secret C:\\Users\\someone\\repo 补丁不匹配");

        service.record(runId, execution);

        ArgumentCaptor<TaskRunWorkerExecutionEntity> captured =
                ArgumentCaptor.forClass(TaskRunWorkerExecutionEntity.class);
        verify(executions).insert(captured.capture());
        assertEquals(runId, captured.getValue().getTaskRunId());
        assertEquals("FILE_PATCH_FAILED", captured.getValue().getFailureCode());
        assertFalse(captured.getValue().getFailureReason().contains("secret"));
        assertFalse(captured.getValue().getFailureReason().contains("C:\\Users"));
    }

    @Test
    void replacesUnknownWorkerFailureWithStableInfrastructureSummary() {
        TaskRunWorkerExecutionMapper executions = mock(TaskRunWorkerExecutionMapper.class);
        TaskRunMapper runs = mock(TaskRunMapper.class);
        TaskRunWorkerExecutionService service = new TaskRunWorkerExecutionService(executions, runs);
        UUID runId = UUID.randomUUID();
        TaskRunEntity run = new TaskRunEntity();
        run.setId(runId);
        run.setProjectId(UUID.randomUUID());
        run.setTaskId(UUID.randomUUID());
        when(runs.selectById(runId)).thenReturn(run);

        WorkerToolExecution execution = new WorkerToolExecution();
        execution.setId(UUID.randomUUID());
        execution.setStatus("FAILED");
        execution.setFailureCode("WORKER_PROVIDER_ERROR");
        execution.setFailureReason("Access denied at https://provider.example/error");

        service.record(runId, execution);

        ArgumentCaptor<TaskRunWorkerExecutionEntity> captured =
                ArgumentCaptor.forClass(TaskRunWorkerExecutionEntity.class);
        verify(executions).insert(captured.capture());
        assertEquals("FAILED_INFRASTRUCTURE", captured.getValue().getFailureCode());
        assertEquals("执行基础设施暂不可用", captured.getValue().getFailureReason());
    }
}
