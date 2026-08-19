package qg.qgent.sandboxworker.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspacePathResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesControlledLocalAndDockerHostPaths() throws Exception {
        Path localRoot = Files.createDirectory(temporaryDirectory.resolve("local"));
        String workspaceId = java.util.UUID.randomUUID().toString();
        Path workspace = Files.createDirectory(localRoot.resolve(workspaceId));
        Path hostRoot = temporaryDirectory.resolve("host");
        WorkspacePathResolver resolver = resolver(localRoot, hostRoot);

        assertEquals(workspace.toRealPath(), resolver.resolveLocal("workspaces/" + workspaceId));
        assertEquals(hostRoot.resolve(workspaceId).toAbsolutePath().normalize(),
                resolver.resolveDockerHost("workspaces/" + workspaceId));
    }

    @Test
    void rejectsTraversalAndMissingWorkspace() throws Exception {
        Path localRoot = Files.createDirectory(temporaryDirectory.resolve("local"));
        WorkspacePathResolver resolver = resolver(localRoot, temporaryDirectory.resolve("host"));

        assertThrows(WorkerException.class, () -> resolver.resolveLocal("workspaces/../outside"));
        assertThrows(WorkerException.class, () -> resolver.resolveLocal("workspaces/missing"));
    }

    @Test
    void resolvesOnlyRegisteredRepositoryMount() throws Exception {
        Path localRoot = Files.createDirectory(temporaryDirectory.resolve("local-repos"));
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        Path repository = Files.createDirectories(localRoot.resolve(workspaceId.toString()).resolve("backend"));
        Path hostRoot = temporaryDirectory.resolve("host-repos");
        WorkspacePathResolver resolver = resolver(localRoot, hostRoot);
        SandboxAllocation allocation = new SandboxAllocation(UUID.randomUUID(), UUID.randomUUID(),
                "workspaces/" + workspaceId, "dev-tools", "READY", "FAKE", Instant.EPOCH,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, Duration.ofMinutes(1), null,
                Map.of(repositoryId, "backend"));

        assertEquals(repository.toRealPath(), resolver.resolveRepositoryLocal(allocation, repositoryId));
        assertEquals(hostRoot.resolve(workspaceId.toString()).resolve("backend").toAbsolutePath().normalize(),
                resolver.resolveRepositoryDockerHost(allocation, repositoryId));
        assertEquals("/workspace/backend", resolver.resolveRepositoryContainer(allocation, repositoryId));
        assertThrows(WorkerException.class, () -> resolver.resolveRepositoryLocal(allocation, UUID.randomUUID()));
    }

    private WorkspacePathResolver resolver(Path localRoot, Path hostRoot) {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setWorkspaceLocalRoot(localRoot.toString());
        properties.setWorkspaceDockerHostRoot(hostRoot.toString());
        return new WorkspacePathResolver(properties);
    }
}
