package qg.qgent.sandboxworker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.api.CreateSandboxRequest;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.runtime.FakeContainerRuntime;
import qg.qgent.sandboxworker.workspace.WorkspaceOperationLock;
import qg.qgent.sandboxworker.workspace.WorkspaceMetadataStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SandboxCleanupServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void cleanupCancelsToolsAndDestroysExpiredSandbox() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setWorkspaceMetadataRoot(temporaryDirectory.resolve("metadata").toString());
        FakeContainerRuntime runtime = new FakeContainerRuntime();
        Clock clock = Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC);
        SandboxService sandboxes = new SandboxService(runtime, properties, clock,
                new WorkspaceOperationLock(properties), mock(WorkspaceMetadataStore.class));
        ToolExecutionService executions = mock(ToolExecutionService.class);
        SandboxCleanupService cleanup = new SandboxCleanupService(sandboxes, executions);
        CreateSandboxRequest request = request();
        sandboxes.create(request);
        runtime.find(request.getSandboxId()).orElseThrow().setExpiresAt(clock.instant().minusSeconds(1));

        cleanup.cleanupExpiredSandboxes();

        verify(executions).cancelBySandbox(request.getSandboxId());
        assertTrue(sandboxes.find(request.getSandboxId()).isEmpty());
    }

    private CreateSandboxRequest request() {
        CreateSandboxRequest request = new CreateSandboxRequest();
        request.setSandboxId(UUID.randomUUID());
        request.setTaskRunId(UUID.randomUUID());
        request.setWorkspaceStorageKey("workspaces/" + UUID.randomUUID());
        request.setImageProfile("dev-tools");
        return request;
    }
}
