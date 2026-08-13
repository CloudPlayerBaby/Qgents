package qg.qgent.sandboxworker.runtime;

import com.github.dockerjava.api.model.AccessMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SandboxBindFactoryTest {
    @TempDir Path root;

    @Test
    void mountsRepositoryRwAndOverlaysGitPointerReadOnly() throws Exception {
        UUID workspaceId = UUID.randomUUID();
        UUID repositoryId = UUID.randomUUID();
        Path localRoot = Files.createDirectory(root.resolve("local"));
        Path workspace = Files.createDirectories(localRoot.resolve(workspaceId.toString()));
        Files.createDirectory(workspace.resolve("backend"));
        Files.write(workspace.resolve(WorkspacePathResolver.GIT_MARKER), new byte[0]);
        Path hostRoot = root.resolve("host");
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setWorkspaceLocalRoot(localRoot.toString());
        properties.setWorkspaceDockerHostRoot(hostRoot.toString());
        SandboxAllocation allocation = new SandboxAllocation(UUID.randomUUID(), UUID.randomUUID(),
                "workspaces/" + workspaceId, "java-node", "READY", "DOCKER", Instant.EPOCH,
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, Duration.ofMinutes(1), null,
                Map.of(repositoryId, "backend"));

        var binds = new SandboxBindFactory(new WorkspacePathResolver(properties)).create(allocation);

        assertEquals(2, binds.size());
        assertEquals("/workspace/backend", binds.get(0).getVolume().getPath());
        assertEquals(AccessMode.rw, binds.get(0).getAccessMode());
        assertEquals("/workspace/backend/.git", binds.get(1).getVolume().getPath());
        assertEquals(AccessMode.ro, binds.get(1).getAccessMode());
        assertEquals(hostRoot.resolve(workspaceId.toString()).resolve(WorkspacePathResolver.GIT_MARKER).toString(),
                binds.get(1).getPath());
    }
}
