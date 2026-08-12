package qg.qgent.sandboxworker.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceManagerServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void provisionsQueriesAndDeletesWorkspace() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        SandboxWorkerProperties properties = properties();
        prepareBareRepository(Path.of(properties.getGitStoreRoot()).resolve(repositoryId + ".git"));
        WorkspaceManagerService service = new WorkspaceManagerService(properties,
                new GitWorktreeManager(properties), new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC));
        WorkspaceProvisionRequest request = request(repositoryId);

        WorkspaceResponse created = service.provision(workspaceId, request);
        WorkspaceResponse replay = service.provision(workspaceId, request);
        WorkspaceResponse queried = service.get(workspaceId);

        assertEquals("READY", created.getStatus());
        assertEquals(created.getRepositories().getFirst().getHeadCommit(),
                replay.getRepositories().getFirst().getHeadCommit());
        assertEquals(created.getRepositories().getFirst().getHeadCommit(),
                queried.getRepositories().getFirst().getHeadCommit());
        assertTrue(Files.isRegularFile(Path.of(properties.getWorkspaceLocalRoot())
                .resolve(workspaceId.toString()).resolve("backend").resolve("README.md")));

        service.delete(workspaceId);

        assertFalse(Files.exists(Path.of(properties.getWorkspaceLocalRoot()).resolve(workspaceId.toString())));
        assertFalse(Files.exists(Path.of(properties.getWorkspaceMetadataRoot()).resolve(workspaceId + ".json")));
        assertTrue(Files.isDirectory(Path.of(properties.getGitStoreRoot()).resolve(repositoryId + ".git")));
    }

    private SandboxWorkerProperties properties() throws Exception {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setWorkspaceLocalRoot(Files.createDirectory(temporaryDirectory.resolve("workspaces")).toString());
        properties.setWorkspaceMetadataRoot(Files.createDirectory(temporaryDirectory.resolve("metadata")).toString());
        properties.setGitStoreRoot(Files.createDirectory(temporaryDirectory.resolve("git-store")).toString());
        return properties;
    }

    private WorkspaceProvisionRequest request(UUID repositoryId) {
        WorkspaceRepositoryRequest repository = new WorkspaceRepositoryRequest();
        repository.setRepositoryId(repositoryId);
        repository.setBaseRef("main");
        repository.setSourceBranch("feat/workspace-test");
        repository.setWorkspacePath("backend");
        WorkspaceProvisionRequest request = new WorkspaceProvisionRequest();
        request.setProjectId(UUID.randomUUID());
        request.setRepositories(List.of(repository));
        return request;
    }

    private void prepareBareRepository(Path bareRepository) throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        run(List.of("git", "init", "-b", "main", source.toString()), temporaryDirectory);
        Files.writeString(source.resolve("README.md"), "workspace test");
        run(List.of("git", "add", "README.md"), source);
        run(List.of("git", "-c", "user.name=Qgents Test", "-c", "user.email=test@qgents.local",
                "commit", "-m", "initial"), source);
        run(List.of("git", "clone", "--bare", source.toString(), bareRepository.toString()), temporaryDirectory);
    }

    private void run(List<String> command, Path directory) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (process.waitFor() != 0) {
            throw new AssertionError("Git 测试准备失败：" + output);
        }
    }
}
