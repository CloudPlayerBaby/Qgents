package qg.qgent.sandboxworker.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitRepositoryManagerTest {
    @TempDir Path root;

    @Test
    void copiesExactUncommittedSnapshotWithoutChangingLiveWorktreeAndCleanupIsIdempotent() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        Path store = root.resolve("snapshot-store").resolve(repositoryId + ".git");
        Path seed = root.resolve("snapshot-seed");
        Files.createDirectories(store.getParent());
        run(List.of("git", "init", "-b", "main", seed.toString()), root);
        Files.writeString(seed.resolve("tracked.txt"), "base\n");
        Files.writeString(seed.resolve("deleted.txt"), "delete me\n");
        run(List.of("git", "-C", seed.toString(), "add", "-A"), root);
        run(List.of("git", "-C", seed.toString(), "-c", "user.name=Test", "-c",
                "user.email=test@example.com", "commit", "-m", "base"), root);
        run(List.of("git", "clone", "--bare", seed.toString(), store.toString()), root);
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setGitStoreRoot(store.getParent().toString());
        GitRepositoryManager manager = new GitRepositoryManager(properties);
        Path live = root.resolve("live/repository"), snapshot = root.resolve("snapshot/repository");
        manager.create(repositoryId, live, "main", "feat/live");
        manager.create(repositoryId, snapshot, "main", "qgents-test-" + UUID.randomUUID());
        Files.writeString(live.resolve("tracked.txt"), "changed\n");
        Files.delete(live.resolve("deleted.txt"));
        Files.writeString(live.resolve("untracked.txt"), "new\n");
        String liveHash = manager.diff(live).getDiffHash();

        try {
            manager.copyWorkingTreeSnapshot(repositoryId, live, snapshot);

            assertEquals(liveHash, manager.diff(snapshot).getDiffHash());
            assertEquals("changed", Files.readString(live.resolve("tracked.txt")).trim());
            assertFalse(Files.exists(snapshot.resolve("deleted.txt")));
            assertEquals("new", Files.readString(snapshot.resolve("untracked.txt")).trim());
        } finally {
            manager.remove(repositoryId, snapshot);
            manager.remove(repositoryId, snapshot);
            manager.remove(repositoryId, live);
        }
    }

    @Test
    void mergePreviewReportsRealGitConflict() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        Path store = root.resolve("merge-store").resolve(repositoryId + ".git");
        Path source = root.resolve("merge-source");
        Files.createDirectories(store.getParent());
        run(List.of("git", "init", "-b", "main", source.toString()), root);
        Files.writeString(source.resolve("value.txt"), "base\n");
        run(List.of("git", "-C", source.toString(), "add", "-A"), root);
        run(List.of("git", "-C", source.toString(), "-c", "user.name=Test", "-c",
                "user.email=test@example.com", "commit", "-m", "base"), root);
        run(List.of("git", "-C", source.toString(), "checkout", "-b", "feat/conflict"), root);
        Files.writeString(source.resolve("value.txt"), "source\n");
        run(List.of("git", "-C", source.toString(), "add", "-A"), root);
        run(List.of("git", "-C", source.toString(), "-c", "user.name=Test", "-c",
                "user.email=test@example.com", "commit", "-m", "source"), root);
        run(List.of("git", "-C", source.toString(), "checkout", "main"), root);
        Files.writeString(source.resolve("value.txt"), "target\n");
        run(List.of("git", "-C", source.toString(), "add", "-A"), root);
        run(List.of("git", "-C", source.toString(), "-c", "user.name=Test", "-c",
                "user.email=test@example.com", "commit", "-m", "target"), root);
        run(List.of("git", "clone", "--bare", source.toString(), store.toString()), root);

        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setGitStoreRoot(store.getParent().toString());
        GitRepositoryManager manager = new GitRepositoryManager(properties);

        assertEquals(output(List.of("git", "--git-dir", store.toString(), "rev-parse", "main"), root).trim(),
                manager.resolveRef(repositoryId, "main"));

        var response = manager.mergePreview(repositoryId, "feat/conflict", "main");

        assertFalse(response.isMergeable());
        assertTrue(response.getConflicts().contains("value.txt"));
    }

    @Test
    void createsLinkedWorktreeAndCommitsReviewedDiff() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        Path store = root.resolve("store").resolve(repositoryId + ".git");
        Path source = root.resolve("source");
        Files.createDirectories(store.getParent());
        run(List.of("git", "init", "-b", "main", source.toString()), root);
        Files.writeString(source.resolve("README.md"), "base\n");
        run(List.of("git", "-C", source.toString(), "add", "-A"), root);
        run(List.of("git", "-C", source.toString(), "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "base"), root);
        run(List.of("git", "clone", "--bare", source.toString(), store.toString()), root);

        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setGitStoreRoot(store.getParent().toString());
        properties.setBackendUrl("http://localhost:8080");
        
        org.springframework.web.client.RestClient.Builder builder = org.springframework.web.client.RestClient.builder()
            .requestFactory(new org.springframework.http.client.ClientHttpRequestFactory() {
                @Override
                public org.springframework.http.client.ClientHttpRequest createRequest(java.net.URI uri, org.springframework.http.HttpMethod httpMethod) {
                    return new org.springframework.mock.http.client.MockClientHttpRequest(httpMethod, uri) {
                        @Override
                        protected org.springframework.http.client.ClientHttpResponse executeInternal() {
                            String responseBody = "{\"token\":\"fake-token\"}";
                            org.springframework.mock.http.client.MockClientHttpResponse response = new org.springframework.mock.http.client.MockClientHttpResponse(
                                responseBody.getBytes(), org.springframework.http.HttpStatus.OK);
                            response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                            return response;
                        }
                    };
                }
            });

        GitRepositoryManager manager = new GitRepositoryManager(properties, builder.build());
        Path worktree = root.resolve("workspace/backend");
        GitRepositoryManager.WorktreeResult created = manager.create(repositoryId, worktree, "main", "feat/test");

        assertTrue(Files.isRegularFile(worktree.resolve(".git")));
        assertEquals(created.baseCommit(), created.headCommit());
        Files.writeString(worktree.resolve("README.md"), "changed\n");
        Files.writeString(worktree.resolve("new.txt"), "new\n");
        GitStatusResponse status = manager.status(worktree);
        GitDiffResponse diff = manager.diff(worktree);
        assertFalse(status.isClean());
        assertTrue(diff.getPatch().contains("new.txt"));
        assertEquals("MODIFIED", diff.getFiles().stream()
                .filter(file -> file.getPath().equals("README.md")).findFirst().orElseThrow().getChangeType());
        assertEquals("ADDED", diff.getFiles().stream()
                .filter(file -> file.getPath().equals("new.txt")).findFirst().orElseThrow().getChangeType());

        GitCommitRequest request = new GitCommitRequest();
        request.setExpectedHeadCommit(created.headCommit());
        request.setExpectedDiffHash(diff.getDiffHash());
        request.setMessage("feat(test): commit reviewed diff");
        request.setOperationId(UUID.randomUUID().toString());
        GitCommitResponse committed = manager.commit(worktree, request);
        assertEquals(committed.getCommitSha(), manager.head(worktree));
        assertTrue(manager.status(worktree).isClean());
        assertEquals(committed.getCommitSha(), manager.commit(worktree, request).getCommitSha(),
                "相同 operationId 重试必须返回已创建的 Commit");

        Path remote = root.resolve("remote.git");
        run(List.of("git", "init", "--bare", remote.toString()), root);
        run(List.of("git", "--git-dir", store.toString(), "remote", "set-url", "origin", remote.toString()), root);
        GitPushRequest pushRequest = new GitPushRequest();
        pushRequest.setExpectedHeadCommit(committed.getCommitSha());
        pushRequest.setCredentialGrantId("grant123");
        qg.qgent.sandboxworker.api.WorkerException exception = org.junit.jupiter.api.Assertions.assertThrows(
                qg.qgent.sandboxworker.api.WorkerException.class,
                () -> manager.push(repositoryId, worktree, "feat/test", pushRequest));
        assertEquals("GIT_ORIGIN_INVALID", exception.getCode());

        manager.remove(repositoryId, worktree);
        assertFalse(Files.exists(worktree));
    }

    @Test
    void cleansUpAskpassScriptOnFailure() throws Exception {
        UUID repositoryId = UUID.randomUUID();
        Path store = root.resolve("store2").resolve(repositoryId + ".git");
        Path source = root.resolve("source2");
        Files.createDirectories(store.getParent());
        run(List.of("git", "init", "-b", "main", source.toString()), root);
        Files.writeString(source.resolve("README.md"), "base\n");
        run(List.of("git", "-C", source.toString(), "add", "-A"), root);
        run(List.of("git", "-C", source.toString(), "-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "base"), root);
        run(List.of("git", "clone", "--bare", source.toString(), store.toString()), root);

        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setGitStoreRoot(store.getParent().toString());
        properties.setBackendUrl("http://localhost:8080");

        org.springframework.web.client.RestClient.Builder builder = org.springframework.web.client.RestClient.builder()
            .requestFactory(new org.springframework.http.client.ClientHttpRequestFactory() {
                @Override
                public org.springframework.http.client.ClientHttpRequest createRequest(java.net.URI uri, org.springframework.http.HttpMethod httpMethod) {
                    return new org.springframework.mock.http.client.MockClientHttpRequest(httpMethod, uri) {
                        @Override
                        protected org.springframework.http.client.ClientHttpResponse executeInternal() {
                            String responseBody = "{\"token\":\"fake-token\"}";
                            org.springframework.mock.http.client.MockClientHttpResponse response = new org.springframework.mock.http.client.MockClientHttpResponse(
                                responseBody.getBytes(), org.springframework.http.HttpStatus.OK);
                            response.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
                            return response;
                        }
                    };
                }
            });

        FailingPushGitRepositoryManager manager = new FailingPushGitRepositoryManager(properties, builder.build());
        Path worktree = root.resolve("workspace2/backend");
        GitRepositoryManager.WorktreeResult created = manager.create(repositoryId, worktree, "main", "feat/fail");

        Files.writeString(worktree.resolve("fail.txt"), "fail\n");
        GitCommitRequest request = new GitCommitRequest();
        request.setExpectedHeadCommit(created.headCommit());
        request.setExpectedDiffHash(manager.diff(worktree).getDiffHash());
        request.setMessage("fail commit");
        request.setOperationId(UUID.randomUUID().toString());
        GitCommitResponse committed = manager.commit(worktree, request);

        // Intentionally set origin to a non-existent remote to trigger push failure
        run(List.of("git", "--git-dir", store.toString(), "remote", "set-url", "origin",
                "https://github.com/qgents/nonexistent.git"), root);
        
        GitPushRequest pushRequest = new GitPushRequest();
        pushRequest.setExpectedHeadCommit(committed.getCommitSha());
        pushRequest.setCredentialGrantId("grant123");

        Path tmpdir = Path.of(System.getProperty("java.io.tmpdir"));
        long beforeCount = 0;
        try (java.util.stream.Stream<Path> stream = Files.list(tmpdir)) {
            beforeCount = stream.filter(p -> p.getFileName().toString().startsWith("git-askpass-")).count();
        }

        org.junit.jupiter.api.Assertions.assertThrows(qg.qgent.sandboxworker.api.WorkerException.class, () -> {
            manager.push(repositoryId, worktree, "feat/fail", pushRequest);
        });
        assertTrue(manager.pushAttempted, "Expected the controlled Git push command to be attempted");

        long afterCount = 0;
        try (java.util.stream.Stream<Path> stream = Files.list(tmpdir)) {
            afterCount = stream.filter(p -> p.getFileName().toString().startsWith("git-askpass-")).count();
        }
        assertEquals(beforeCount, afterCount, "GIT_ASKPASS script was not cleaned up!");
    }

    @Test
    void readerJoinIsBoundedWhenPipeHolderDoesNotExit() throws Exception {
        Thread blocked = Thread.ofVirtual().start(() -> {
            try { Thread.sleep(Duration.ofMinutes(1)); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        long started = System.nanoTime();
        try {
            assertFalse(GitRepositoryManager.joinReaders(blocked));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(7)) < 0);
        } finally {
            blocked.interrupt();
            blocked.join(Duration.ofSeconds(1));
        }
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
        if (process.waitFor() != 0) throw new AssertionError(output);
        return output;
    }

    /** 使用合法 GitHub origin 但在进程启动前模拟受控 push 失败，避免测试访问网络。 */
    private static final class FailingPushGitRepositoryManager extends GitRepositoryManager {
        private boolean pushAttempted;

        private FailingPushGitRepositoryManager(SandboxWorkerProperties properties,
                org.springframework.web.client.RestClient restClient) {
            super(properties, restClient);
        }

        @Override
        CommandResult run(List<String> command, Map<String, String> environment) {
            if (command.contains("push")) {
                pushAttempted = true;
                assertTrue(environment.containsKey("GIT_ASKPASS"));
                assertTrue(environment.containsKey("QGENTS_GIT_TOKEN"));
                return new CommandResult(1, "", "simulated push failure");
            }
            return super.run(command, environment);
        }
    }
}
