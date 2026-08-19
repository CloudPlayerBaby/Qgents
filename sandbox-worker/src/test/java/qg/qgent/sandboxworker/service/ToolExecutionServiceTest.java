package qg.qgent.sandboxworker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import qg.qgent.sandboxworker.api.ToolExecutionRequest;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.persistence.ToolExecutionEntity;
import qg.qgent.sandboxworker.persistence.ToolExecutionLogEntity;
import qg.qgent.sandboxworker.persistence.ToolExecutionLogMapper;
import qg.qgent.sandboxworker.persistence.ToolExecutionMapper;
import qg.qgent.sandboxworker.runtime.SandboxAllocation;
import qg.qgent.sandboxworker.runtime.WorkspacePathResolver;
import qg.qgent.sandboxworker.tool.ToolRegistry;
import qg.qgent.sandboxworker.tool.ToolResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolExecutionServiceTest {
    private final ExecutorService pool = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
    }

    @Test
    void submitPersistsQueuedRecordAndCompletesInBackground() throws Exception {
        Map<String, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ArrayList<ToolExecutionLogEntity> logs = new ArrayList<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(logs);
        SandboxService sandboxes = mock(SandboxService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        UUID sandboxId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        SandboxAllocation sandbox = allocation(sandboxId);
        when(sandboxes.findAllocation(sandboxId)).thenReturn(sandbox);
        when(tools.requiresRepository("process.exec")).thenReturn(true);
        when(tools.execute(anyString(), any(), any())).thenReturn(ToolResult.value(Map.of("ok", true)));
        ToolExecutionService service = new ToolExecutionService(sandboxes, paths, tools, executions, logMapper,
                new ObjectMapper().findAndRegisterModules(), properties, pool, Clock.systemUTC());
        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setExecutionId(executionId);
        request.setTool("process.exec");
        request.setRepositoryId(UUID.randomUUID());

        String submitted = service.submit(sandboxId, request).getStatus();
        assertTrue(java.util.List.of("QUEUED", "RUNNING", "SUCCEEDED").contains(submitted));
        for (int attempt = 0; attempt < 100 && !"SUCCEEDED".equals(rows.get(executionId.toString()).getStatus()); attempt++) {
            Thread.sleep(10);
        }

        assertEquals("SUCCEEDED", service.find(executionId).getStatus());
        assertFalse(service.logs(executionId, 0, 100).getItems().isEmpty());
    }

    @Test
    void queuedExecutionCanBeCancelledAndTerminalExecutionCannotBeCancelledAgain() {
        Map<String, ToolExecutionEntity> rows = new ConcurrentHashMap<>();
        ArrayList<ToolExecutionLogEntity> logs = new ArrayList<>();
        ToolExecutionMapper executions = executionMapper(rows);
        ToolExecutionLogMapper logMapper = logMapper(logs);
        SandboxService sandboxes = mock(SandboxService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
        ExecutorService waitingPool = mock(ExecutorService.class);
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        UUID sandboxId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        when(sandboxes.findAllocation(sandboxId)).thenReturn(allocation(sandboxId));
        when(tools.requiresRepository("process.exec")).thenReturn(true);
        ToolExecutionService service = new ToolExecutionService(sandboxes, paths, tools, executions, logMapper,
                new ObjectMapper().findAndRegisterModules(), properties, waitingPool, Clock.systemUTC());
        ToolExecutionRequest request = new ToolExecutionRequest();
        request.setExecutionId(executionId);
        request.setTool("process.exec");
        request.setRepositoryId(UUID.randomUUID());

        service.submit(sandboxId, request);

        assertEquals("CANCELLED", service.cancel(executionId).getStatus());
        WorkerException exception = assertThrows(WorkerException.class, () -> service.cancel(executionId));
        assertEquals("EXECUTION_NOT_CANCELLABLE", exception.getCode());
    }

    private ToolExecutionMapper executionMapper(Map<String, ToolExecutionEntity> rows) {
        ToolExecutionMapper mapper = mock(ToolExecutionMapper.class);
        when(mapper.selectById(anyString())).thenAnswer(invocation -> rows.get(invocation.getArgument(0)));
        when(mapper.markInterrupted(anyString(), any())).thenReturn(0);
        when(mapper.insert(any(ToolExecutionEntity.class))).thenAnswer(invocation -> {
            ToolExecutionEntity entity = invocation.getArgument(0);
            rows.put(entity.getId(), entity);
            return 1;
        });
        when(mapper.markRunning(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
            if (entity == null || !"QUEUED".equals(entity.getStatus())) return 0;
            entity.setStatus("RUNNING");
            entity.setStartedAt(invocation.getArgument(2));
            return 1;
        });
        when(mapper.finishIfRunning(anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
            ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
            if (entity == null || !"RUNNING".equals(entity.getStatus())) return 0;
            entity.setStatus(invocation.getArgument(2));
            entity.setExitCode(invocation.getArgument(3));
            entity.setResultJson(invocation.getArgument(4));
            entity.setFailureReason(invocation.getArgument(5));
            entity.setFinishedAt(invocation.getArgument(6));
            return 1;
        });
        when(mapper.markCancelled(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ToolExecutionEntity entity = rows.get(invocation.getArgument(0));
            if (entity == null || !java.util.List.of("QUEUED", "RUNNING").contains(entity.getStatus())) return 0;
            entity.setStatus("CANCELLED");
            entity.setFailureReason("执行已取消");
            entity.setFinishedAt(invocation.getArgument(2));
            return 1;
        });
        return mapper;
    }

    private ToolExecutionLogMapper logMapper(ArrayList<ToolExecutionLogEntity> logs) {
        ToolExecutionLogMapper mapper = mock(ToolExecutionLogMapper.class);
        when(mapper.selectMaxSequence(anyString())).thenAnswer(invocation -> (long) logs.size());
        when(mapper.insert(any())).thenAnswer(invocation -> {
            logs.add(invocation.getArgument(0));
            return 1;
        });
        when(mapper.selectAfter(anyString(), any(Long.class), any(Integer.class))).thenAnswer(invocation ->
                logs.stream().filter(item -> item.getSequenceNo() > (long) invocation.getArgument(1)).toList());
        return mapper;
    }

    private SandboxAllocation allocation(UUID sandboxId) {
        Instant now = Instant.now();
        return new SandboxAllocation(sandboxId, UUID.randomUUID(), "workspaces/" + UUID.randomUUID(), "dev-tools",
                "READY", "FAKE", now, now, now.plusSeconds(60), now.plusSeconds(3600),
                Duration.ofMinutes(15), null, Map.of());
    }
}
