package qg.qgent.sandboxworker.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitRepositoryManagerTest {
    @TempDir Path root;

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
        GitRepositoryManager manager = new GitRepositoryManager(properties);
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

        GitCommitRequest request = new GitCommitRequest();
        request.setExpectedHeadCommit(created.headCommit());
        request.setExpectedDiffHash(diff.getDiffHash());
        request.setMessage("feat(test): commit reviewed diff");
        GitCommitResponse committed = manager.commit(worktree, request);
        assertEquals(committed.getCommitSha(), manager.head(worktree));
        assertTrue(manager.status(worktree).isClean());

        Path remote = root.resolve("remote.git");
        run(List.of("git", "init", "--bare", remote.toString()), root);
        run(List.of("git", "--git-dir", store.toString(), "remote", "set-url", "origin", remote.toString()), root);
        GitPushRequest pushRequest = new GitPushRequest();
        pushRequest.setExpectedHeadCommit(committed.getCommitSha());
        GitPushResponse pushed = manager.push(repositoryId, worktree, "feat/test", pushRequest);
        assertTrue(pushed.isVerified());
        assertEquals(committed.getCommitSha(), output(List.of("git", "--git-dir", remote.toString(),
                "rev-parse", "refs/heads/feat/test"), root).trim());

        manager.remove(repositoryId, worktree);
        assertFalse(Files.exists(worktree));
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
}
