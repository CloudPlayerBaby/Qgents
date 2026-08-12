package qg.qgent.sandboxworker.service;

import org.junit.jupiter.api.Test;
import qg.qgent.sandboxworker.api.CreateSandboxRequest;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.runtime.FakeCommandExecutor;
import qg.qgent.sandboxworker.runtime.FakeContainerRuntime;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxCleanupServiceTest {
    @Test
    void cleanupDestroysExpiredSandbox() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        FakeContainerRuntime runtime = new FakeContainerRuntime();
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC);
        SandboxService sandboxes = new SandboxService(runtime, properties, clock);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            ExecutionService executions = new ExecutionService(sandboxes, new FakeCommandExecutor(), properties,
                    pool, scheduler, clock);
            SandboxCleanupService cleanup = new SandboxCleanupService(sandboxes, executions);
            CreateSandboxRequest request = request();
            sandboxes.create(request);
            runtime.find(request.getSandboxId()).orElseThrow().setExpiresAt(clock.instant().minusSeconds(1));

            cleanup.cleanupExpiredSandboxes();

            assertTrue(sandboxes.find(request.getSandboxId()).isEmpty());
        } finally {
            pool.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    private CreateSandboxRequest request() {
        CreateSandboxRequest request = new CreateSandboxRequest();
        request.setSandboxId(UUID.randomUUID());
        request.setTaskRunId(UUID.randomUUID());
        request.setWorkspaceStorageKey("workspaces/example");
        request.setImageProfile("java-node");
        return request;
    }
}
