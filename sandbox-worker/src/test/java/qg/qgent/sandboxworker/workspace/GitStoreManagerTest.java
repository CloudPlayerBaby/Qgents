package qg.qgent.sandboxworker.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Git Store 同步前的本地安全边界测试。 */
class GitStoreManagerTest {
    @TempDir
    Path root;

    @Test
    void rejectsUncontrolledRemoteUrlsBeforeGitOrCredentialUse() {
        GitStoreManager manager = manager();
        for (String url : List.of(
                "file:///tmp/repository.git",
                "ssh://git@github.com/qgents/example.git",
                "https://evil.example/qgents/example.git",
                "https://user:secret@github.com/qgents/example.git",
                "https://github.com:444/qgents/example.git",
                "https://github.com/qgents/example.git?token=secret")) {
            WorkerException exception = assertThrows(WorkerException.class,
                    () -> manager.sync(UUID.randomUUID(), request(url)));
            assertEquals("GIT_REMOTE_URL_INVALID", exception.getCode());
        }
    }

    @Test
    void rejectsExistingNonBareStoreBeforeCredentialExchange() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        Path store = root.resolve("stores").resolve(repositoryId + ".git");
        Files.createDirectories(store);

        WorkerException exception = assertThrows(WorkerException.class,
                () -> manager().sync(repositoryId, request("https://github.com/qgents/example.git")));

        assertEquals("GIT_STORE_INVALID", exception.getCode());
    }

    @Test
    void initializesAndFetchesStoreThenVerifiesExpectedHead() {
        UUID repositoryId = UUID.randomUUID();
        RecordingGitRepositoryManager repositories = new RecordingGitRepositoryManager(root,
                "a".repeat(40));
        GitStoreManager manager = new GitStoreManager(repositories);

        GitStoreSyncResponse response = manager.sync(repositoryId,
                request("https://github.com/qgents/example.git"));

        assertEquals(repositoryId, response.getRepositoryId());
        assertEquals("a".repeat(40), response.getHeadCommit());
        assertEquals(true, response.isCreated());
        assertEquals(true, repositories.commands.stream().anyMatch(command -> command.contains("init") && command.contains("--bare")));
        assertEquals(true, repositories.commands.stream().anyMatch(command -> command.contains("fetch")
                && command.stream().anyMatch(argument -> argument.contains("refs/heads/main"))));
    }

    @Test
    void rejectsDifferentFetchedHead() {
        GitStoreManager manager = new GitStoreManager(new RecordingGitRepositoryManager(root, "b".repeat(40)));

        WorkerException exception = assertThrows(WorkerException.class,
                () -> manager.sync(UUID.randomUUID(), request("https://github.com/qgents/example.git")));

        assertEquals("GIT_REMOTE_SHA_MISMATCH", exception.getCode());
    }

    @Test
    void rejectsInvalidRemoteBranchBeforeCredentialExchange() {
        GitStoreManager manager = manager();
        GitStoreSyncRequest request = request("https://github.com/qgents/example.git");
        request.setRemoteBranch("../main");

        WorkerException exception = assertThrows(WorkerException.class,
                () -> manager.sync(UUID.randomUUID(), request));

        assertEquals("GIT_REMOTE_BRANCH_INVALID", exception.getCode());
    }

    @Test
    void newlyCreatedStoreCleanedOnFetchFailure() {
        UUID repositoryId = UUID.randomUUID();
        Path store = root.resolve("stores").resolve(repositoryId + ".git");
        GitStoreManager manager = new GitStoreManager(failingFetchRecording(root, "a".repeat(40)));

        WorkerException exception = assertThrows(WorkerException.class,
                () -> manager.sync(repositoryId, request("https://github.com/qgents/example.git")));

        assertEquals("GIT_STORE_FETCH_FAILED", exception.getCode());
        assertEquals(false, Files.exists(store));
    }

    @Test
    void existingStorePreservedOnFetchFailure() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        Path store = root.resolve("stores").resolve(repositoryId + ".git");
        Files.createDirectories(store);

        GitStoreManager manager = new GitStoreManager(failingFetchRecording(root, "a".repeat(40)));

        WorkerException exception = assertThrows(WorkerException.class,
                () -> manager.sync(repositoryId, request("https://github.com/qgents/example.git")));

        assertEquals("GIT_STORE_FETCH_FAILED", exception.getCode());
        assertEquals(true, Files.exists(store));
    }

    private GitRepositoryManager failingFetchRecording(Path root, String fetchedHead) {
        return new RecordingGitRepositoryManager(root, fetchedHead, true);
    }

    private GitStoreManager manager() {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setGitStoreRoot(root.resolve("stores").toString());
        return new GitStoreManager(new GitRepositoryManager(properties,
                org.springframework.web.client.RestClient.builder().build()));
    }

    private GitStoreSyncRequest request(String url) {
        GitStoreSyncRequest request = new GitStoreSyncRequest();
        request.setRepositoryUrl(url);
        request.setRemoteBranch("main");
        request.setExpectedHeadCommit("a".repeat(40));
        request.setCredentialGrantId("grant-test");
        return request;
    }

    private static final class RecordingGitRepositoryManager extends GitRepositoryManager {
        private final Path storeRoot;
        private final String fetchedHead;
        private final boolean failFetch;
        private final java.util.ArrayList<List<String>> commands = new java.util.ArrayList<>();

        private RecordingGitRepositoryManager(Path root, String fetchedHead) {
            this(root, fetchedHead, false);
        }

        private RecordingGitRepositoryManager(Path root, String fetchedHead, boolean failFetch) {
            super(properties(root), org.springframework.web.client.RestClient.builder().build());
            this.storeRoot = root.resolve("stores");
            this.fetchedHead = fetchedHead;
            this.failFetch = failFetch;
        }

        @Override
        <T> T locked(UUID repositoryId, java.util.function.Supplier<T> action) {
            return action.get();
        }

        @Override
        Path gitStore(UUID repositoryId) {
            return storeRoot.resolve(repositoryId + ".git");
        }

        @Override
        CommandResult run(List<String> command, Map<String, String> environment) {
            commands.add(command);
            if (command.contains("init")) {
                try {
                    Files.createDirectories(Path.of(command.getLast()));
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
                return new CommandResult(0, "", "");
            }
            if (command.contains("rev-parse") && command.contains("--is-bare-repository")) {
                return new CommandResult(0, "true\n", "");
            }
            if (command.contains("rev-parse") && command.contains("--verify")) {
                return new CommandResult(0, fetchedHead + "\n", "");
            }
            if (command.contains("remote") && command.contains("get-url")) {
                return new CommandResult(2, "", "");
            }
            if (command.contains("fetch")) {
                return failFetch ? new CommandResult(1, "", "fetch failed") : new CommandResult(0, "", "");
            }
            return new CommandResult(0, "", "");
        }

        @Override
        <T> T withCredential(String grantId, String headCommit, String repositoryFullName, String branchName,
                String purpose,
                java.util.function.Function<Map<String, String>, T> action) {
            return action.apply(Map.of("GIT_ASKPASS", "fake", "GIT_TERMINAL_PROMPT", "0"));
        }

        private static SandboxWorkerProperties properties(Path root) {
            SandboxWorkerProperties properties = new SandboxWorkerProperties();
            properties.setGitStoreRoot(root.resolve("stores").toString());
            return properties;
        }
    }
}
