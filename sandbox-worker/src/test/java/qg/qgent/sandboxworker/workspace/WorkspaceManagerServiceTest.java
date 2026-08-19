package qg.qgent.sandboxworker.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.api.CreateSandboxRequest;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.runtime.FakeContainerRuntime;
import qg.qgent.sandboxworker.service.SandboxService;

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
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceManagerServiceTest {
    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void makeGitFilesDeletableOnWindows() throws Exception {
        try (var paths = Files.walk(temporaryDirectory)) {
            for (Path path : paths.toList()) {
                try { Files.setAttribute(path, "dos:readonly", false); } catch (Exception ignored) { }
            }
        }
    }

    @Test
    void provisionsQueriesAndDeletesWorkspace() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        SandboxWorkerProperties properties = properties();
        prepareBareRepository(Path.of(properties.getGitStoreRoot()).resolve(repositoryId + ".git"));
        WorkspaceManagerService service = service(properties, new FakeContainerRuntime());
        WorkspaceProvisionRequest request = request(repositoryId);

        WorkspaceResponse created = service.provision(workspaceId, request);
        WorkspaceResponse queried = service.get(workspaceId);

        assertEquals("READY", created.getStatus());
        assertEquals(created.getRepositories().getFirst().getHeadCommit(),
                queried.getRepositories().getFirst().getHeadCommit());
        assertTrue(Files.isRegularFile(Path.of(properties.getWorkspaceLocalRoot())
                .resolve(workspaceId.toString()).resolve("backend").resolve("README.md")));
        assertTrue(Files.isRegularFile(Path.of(properties.getWorkspaceLocalRoot())
                .resolve(workspaceId.toString()).resolve("backend").resolve(".git")));
        assertEquals(0, Files.size(Path.of(properties.getWorkspaceLocalRoot()).resolve(workspaceId.toString())
                .resolve(qg.qgent.sandboxworker.runtime.WorkspacePathResolver.GIT_MARKER)));
        assertTrue(output(List.of("git", "--git-dir", Path.of(properties.getGitStoreRoot()).resolve(repositoryId + ".git").toString(),
                "worktree", "list", "--porcelain"), temporaryDirectory).contains("backend"));

        service.delete(workspaceId);

        assertFalse(Files.exists(Path.of(properties.getWorkspaceLocalRoot()).resolve(workspaceId.toString())));
        assertFalse(Files.exists(Path.of(properties.getWorkspaceMetadataRoot()).resolve(workspaceId + ".json")));
        assertTrue(Files.isDirectory(Path.of(properties.getGitStoreRoot()).resolve(repositoryId + ".git")));
    }

    @Test
    void rejectsDeletionWhileSandboxUsesWorkspace() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        SandboxWorkerProperties properties = properties();
        prepareBareRepository(Path.of(properties.getGitStoreRoot()).resolve(repositoryId + ".git"));
        FakeContainerRuntime runtime = new FakeContainerRuntime();
        WorkspaceOperationLock lock = new WorkspaceOperationLock(properties);
        SandboxService sandboxes = new SandboxService(runtime, properties,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC), lock,
                new WorkspaceMetadataStore(properties, new ObjectMapper().findAndRegisterModules()));
        WorkspaceManagerService service = new WorkspaceManagerService(properties,
                new GitRepositoryManager(properties, org.springframework.web.client.RestClient.builder().build()), sandboxes, lock, new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC));
        service.provision(workspaceId, request(repositoryId));

        CreateSandboxRequest sandbox = new CreateSandboxRequest();
        sandbox.setSandboxId(UUID.randomUUID());
        sandbox.setTaskRunId(UUID.randomUUID());
        sandbox.setWorkspaceStorageKey("workspaces/" + workspaceId);
        sandbox.setImageProfile("dev-tools");
        sandbox.setRepositoryIds(List.of(repositoryId));
        sandboxes.create(sandbox);

        WorkerException exception = assertThrows(WorkerException.class, () -> service.delete(workspaceId));
        assertEquals("WORKSPACE_IN_USE", exception.getCode());
    }

    @Test
    void idempotentProvisionUpgradesExistingWorkspaceWithGitMarker() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        SandboxWorkerProperties properties = properties();
        prepareBareRepository(Path.of(properties.getGitStoreRoot()).resolve(repositoryId + ".git"));
        WorkspaceManagerService service = service(properties, new FakeContainerRuntime());
        WorkspaceProvisionRequest request = request(repositoryId);
        WorkspaceResponse created = service.provision(workspaceId, request);
        Path workspace = Path.of(properties.getWorkspaceLocalRoot()).resolve(workspaceId.toString());
        Path marker = workspace.resolve(qg.qgent.sandboxworker.runtime.WorkspacePathResolver.GIT_MARKER);
        Files.delete(marker);
        Files.writeString(workspace.resolve("backend").resolve("local-change.txt"), "preserve me");

        WorkspaceResponse retried = service.provision(workspaceId, request);

        assertEquals(created.getId(), retried.getId());
        assertTrue(Files.isRegularFile(marker));
        assertEquals(0, Files.size(marker));
        assertEquals("preserve me", Files.readString(workspace.resolve("backend").resolve("local-change.txt")));
    }

    @Test
    void rejectsTestSnapshotWhenTheSourceHeadChangedAfterTheTestWasRequested() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        UUID workspaceId = UUID.randomUUID();
        SandboxWorkerProperties properties = properties();
        prepareBareRepository(Path.of(properties.getGitStoreRoot()).resolve(repositoryId + ".git"));
        WorkspaceManagerService service = service(properties, new FakeContainerRuntime());
        WorkspaceResponse workspace = service.provision(workspaceId, request(repositoryId));

        WorkerException failure = assertThrows(WorkerException.class,
                () -> service.snapshotForTest(workspaceId, repositoryId, UUID.randomUUID(), workspace.getProjectId(),
                        "f".repeat(40)));

        assertEquals("TEST_SNAPSHOT_HEAD_MISMATCH", failure.getCode());
    }

    private WorkspaceManagerService service(SandboxWorkerProperties properties, FakeContainerRuntime runtime) {
        WorkspaceOperationLock lock = new WorkspaceOperationLock(properties);
        SandboxService sandboxes = new SandboxService(runtime, properties,
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC), lock,
                new WorkspaceMetadataStore(properties, new ObjectMapper().findAndRegisterModules()));
        return new WorkspaceManagerService(properties, new GitRepositoryManager(properties, org.springframework.web.client.RestClient.builder().build()), sandboxes, lock,
                new ObjectMapper().findAndRegisterModules(),
                Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC));
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
        output(command, directory);
    }

    private String output(List<String> command, Path directory) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes());
        }
        if (process.waitFor() != 0) {
            throw new AssertionError("Git 测试准备失败：" + output);
        }
        return output;
    }
}
