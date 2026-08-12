package qg.qgent.sandboxworker.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import qg.qgent.sandboxworker.api.CreateExecutionRequest;
import qg.qgent.sandboxworker.api.CreateSandboxRequest;
import qg.qgent.sandboxworker.api.ExecutionLogsResponse;
import qg.qgent.sandboxworker.api.ExecutionResponse;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.runtime.FakeCommandExecutor;
import qg.qgent.sandboxworker.runtime.FakeContainerRuntime;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ExecutionServiceTest {
    private final SandboxWorkerProperties properties = new SandboxWorkerProperties();
    private final FakeContainerRuntime runtime = new FakeContainerRuntime();
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC);
    private final SandboxService sandboxes = new SandboxService(runtime, properties, clock);
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutionService service = new ExecutionService(sandboxes, new FakeCommandExecutor(), properties,
            pool, scheduler, clock);

    @AfterEach
    void shutdown() {
        pool.shutdownNow();
        scheduler.shutdownNow();
    }

    @Test
    void executesAsynchronouslyAndProvidesCursorLogs() throws Exception {
        CreateSandboxRequest sandbox = sandbox();
        sandboxes.create(sandbox);
        CreateExecutionRequest request = execution(List.of("mvn", "test"));

        service.create(sandbox.getSandboxId(), request);
        ExecutionResponse completed = awaitFinished(request.getExecutionId());
        ExecutionLogsResponse logs = service.logs(request.getExecutionId(), 0, 20);

        assertEquals("SUCCEEDED", completed.getStatus());
        assertEquals(0, completed.getExitCode());
        assertFalse(logs.getItems().isEmpty());
        assertEquals(logs.getItems().get(logs.getItems().size() - 1).getSequence(), logs.getNextCursor());
        assertEquals("READY", sandboxes.find(sandbox.getSandboxId()).orElseThrow().getStatus());
    }

    @Test
    void replayReturnsTheExistingExecution() throws Exception {
        CreateSandboxRequest sandbox = sandbox();
        sandboxes.create(sandbox);
        CreateExecutionRequest request = execution(List.of("mvn", "test"));

        service.create(sandbox.getSandboxId(), request);
        awaitFinished(request.getExecutionId());
        ExecutionResponse replay = service.create(sandbox.getSandboxId(), request);

        assertEquals(request.getExecutionId(), replay.getId());
        assertEquals("SUCCEEDED", replay.getStatus());
    }

    private ExecutionResponse awaitFinished(UUID executionId) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            ExecutionResponse response = service.find(executionId);
            if (List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(response.getStatus())) {
                return response;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("执行未在预期时间内结束");
    }

    private CreateSandboxRequest sandbox() {
        CreateSandboxRequest request = new CreateSandboxRequest();
        request.setSandboxId(UUID.randomUUID());
        request.setTaskRunId(UUID.randomUUID());
        request.setWorkspaceStorageKey("workspaces/example");
        request.setImageProfile("java-node");
        return request;
    }

    private CreateExecutionRequest execution(List<String> command) {
        CreateExecutionRequest request = new CreateExecutionRequest();
        request.setExecutionId(UUID.randomUUID());
        request.setCommand(command);
        return request;
    }
}
