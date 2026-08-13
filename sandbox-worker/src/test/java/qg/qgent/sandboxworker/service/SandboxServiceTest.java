package qg.qgent.sandboxworker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.api.CreateSandboxRequest;
import qg.qgent.sandboxworker.api.ResourceLimitsRequest;
import qg.qgent.sandboxworker.api.SandboxResponse;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.runtime.FakeContainerRuntime;
import qg.qgent.sandboxworker.workspace.WorkspaceOperationLock;
import qg.qgent.sandboxworker.workspace.WorkspaceMetadataStore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void resolvesAuthorizedRepositoryIdsFromWorkspaceMetadata() {
        SandboxWorkerProperties properties = properties();
        WorkspaceMetadataStore metadata = mock(WorkspaceMetadataStore.class);
        UUID repositoryId = UUID.randomUUID();
        CreateSandboxRequest request = request();
        request.setRepositoryIds(java.util.List.of(repositoryId));
        when(metadata.resolveRepositories(request.getWorkspaceStorageKey(), request.getRepositoryIds()))
                .thenReturn(java.util.Map.of(repositoryId, "backend"));

        SandboxService service = new SandboxService(runtime, properties, Clock.fixed(now, ZoneOffset.UTC),
                new WorkspaceOperationLock(properties), metadata);
        service.create(request);

        assertEquals("backend", runtime.find(request.getSandboxId()).orElseThrow()
                .getRepositoryPaths().get(repositoryId));
    }

    @Test
    void rejectsDuplicateRepositoryIds() {
        UUID repositoryId = UUID.randomUUID();
        CreateSandboxRequest request = request();
        request.setRepositoryIds(java.util.List.of(repositoryId, repositoryId));

        var exception = assertThrows(qg.qgent.sandboxworker.api.WorkerException.class,
                () -> service().create(request));

        assertEquals("SANDBOX_REPOSITORY_DUPLICATE", exception.getCode());
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
        WorkspaceMetadataStore metadata = mock(WorkspaceMetadataStore.class);
        when(metadata.resolveRepositories(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(java.util.Map.of());
        return new SandboxService(runtime, properties, Clock.fixed(now, ZoneOffset.UTC),
                new WorkspaceOperationLock(properties), metadata);
    }
}
