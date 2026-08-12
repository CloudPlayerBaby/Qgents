package qg.qgent.sandboxworker.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspacePathResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesControlledLocalAndDockerHostPaths() throws Exception {
        Path localRoot = Files.createDirectory(temporaryDirectory.resolve("local"));
        Path workspace = Files.createDirectory(localRoot.resolve("example"));
        Path hostRoot = temporaryDirectory.resolve("host");
        WorkspacePathResolver resolver = resolver(localRoot, hostRoot);

        assertEquals(workspace.toRealPath(), resolver.resolveLocal("workspaces/example"));
        assertEquals(hostRoot.resolve("example").toAbsolutePath().normalize(),
                resolver.resolveDockerHost("workspaces/example"));
    }

    @Test
    void rejectsTraversalAndMissingWorkspace() throws Exception {
        Path localRoot = Files.createDirectory(temporaryDirectory.resolve("local"));
        WorkspacePathResolver resolver = resolver(localRoot, temporaryDirectory.resolve("host"));

        assertThrows(WorkerException.class, () -> resolver.resolveLocal("workspaces/../outside"));
        assertThrows(WorkerException.class, () -> resolver.resolveLocal("workspaces/missing"));
    }

    private WorkspacePathResolver resolver(Path localRoot, Path hostRoot) {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setWorkspaceLocalRoot(localRoot.toString());
        properties.setWorkspaceDockerHostRoot(hostRoot.toString());
        return new WorkspacePathResolver(properties);
    }
}
