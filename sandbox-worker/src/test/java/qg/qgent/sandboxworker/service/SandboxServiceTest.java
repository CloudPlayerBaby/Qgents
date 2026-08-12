package qg.qgent.sandboxworker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.api.CreateSandboxRequest;
import qg.qgent.sandboxworker.api.ResourceLimitsRequest;
import qg.qgent.sandboxworker.api.SandboxResponse;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.runtime.FakeContainerRuntime;
import qg.qgent.sandboxworker.workspace.WorkspaceOperationLock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxServiceTest {
    @TempDir
    Path temporaryDirectory;

    private final Instant now = Instant.parse("2026-08-12T00:00:00Z");
    private final FakeContainerRuntime runtime = new FakeContainerRuntime();

    @Test
    void createClampsRequestedTtlToWorkerLimits() {
        CreateSandboxRequest request = request();
        ResourceLimitsRequest limits = new ResourceLimitsRequest();
        limits.setIdleTtlSeconds(9999L);
        limits.setMaxLifetimeSeconds(9999L);
        request.setLimits(limits);

        SandboxResponse response = service().create(request);

        assertEquals(now.plus(Duration.ofMinutes(10)), response.getExpiresAt());
        assertEquals(now.plus(Duration.ofHours(1)), response.getMaxExpiresAt());
    }

    @Test
    void expiredSandboxIsSelectedForCleanup() {
        CreateSandboxRequest request = request();
        ResourceLimitsRequest limits = new ResourceLimitsRequest();
        limits.setIdleTtlSeconds(1L);
        request.setLimits(limits);
        SandboxService service = service();
        service.create(request);
        runtime.find(request.getSandboxId()).orElseThrow().setExpiresAt(now.minusSeconds(1));

        assertTrue(service.expiredSandboxIds().contains(request.getSandboxId()));
    }

    private CreateSandboxRequest request() {
        CreateSandboxRequest request = new CreateSandboxRequest();
        request.setSandboxId(UUID.randomUUID());
        request.setTaskRunId(UUID.randomUUID());
        request.setWorkspaceStorageKey("workspaces/" + UUID.randomUUID());
        request.setImageProfile("java-node");
        return request;
    }

    private SandboxWorkerProperties properties() {
        SandboxWorkerProperties value = new SandboxWorkerProperties();
        value.setWorkspaceMetadataRoot(temporaryDirectory.resolve("metadata").toString());
        value.setMaxIdleTtl(Duration.ofMinutes(10));
        value.setMaxLifetime(Duration.ofHours(1));
        return value;
    }

    private SandboxService service() {
        SandboxWorkerProperties properties = properties();
        return new SandboxService(runtime, properties, Clock.fixed(now, ZoneOffset.UTC),
                new WorkspaceOperationLock(properties));
    }
}
