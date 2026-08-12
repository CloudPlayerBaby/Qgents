package qg.qgent.orchestration.tool;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import qg.qgent.entity.WorkspaceRepositoryEntity;
import qg.qgent.mapper.WorkspaceRepositoryMapper;
import qg.qgent.service.WorkspaceService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LocalGitDiffAccess 测试。
 * <ul>
 *   <li>mock 单测：Workspace 未就绪/不存在、无 worktree、路径越界、git 命令失败/非零退出、
 *       空 diff、多 worktree 聚合、headCommit 优先作为 base、服务异常传播；</li>
 *   <li>真实 git 集成测试（@TempDir + 真实 SandboxProcessRunner，git 不可用时跳过）：
 *       正常 diff、空 diff、非 Git workspace。</li>
 * </ul>
 * 不写入任何 Secret，git 命令只读（diff/rev-parse），不执行 commit/push/MR。
 */
class LocalGitDiffAccessTest {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final UUID WS_ID = UUID.randomUUID();

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final WorkspaceRepositoryMapper repositoryMapper = mock(WorkspaceRepositoryMapper.class);
    private final SandboxProcessRunner runner = mock(SandboxProcessRunner.class);

    private LocalGitDiffAccess access() {
        return new LocalGitDiffAccess(workspaceService, repositoryMapper, runner);
    }

    private static WorkspaceRepositoryEntity worktree(String path, String baseCommit, String headCommit) {
        WorkspaceRepositoryEntity worktree = new WorkspaceRepositoryEntity();
        worktree.setWorkspaceId(WS_ID);
        worktree.setWorkspacePath(path);
        worktree.setBaseCommit(baseCommit);
        worktree.setHeadCommit(headCommit);
        return worktree;
    }

    private static WorkspaceService.WorkspaceResolution ready(Path root) {
        return new WorkspaceService.WorkspaceResolution(true, true, root, null);
    }

    // ---------- 单元测试：mock runner ----------

    @Test
    void workspaceNotFoundFailsExplicitlyWithoutRunning() {
        when(workspaceService.resolve(WS_ID))
                .thenReturn(new WorkspaceService.WorkspaceResolution(false, false, null, "workspace not found: " + WS_ID));

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not ready");
        verify(repositoryMapper, never()).selectByWorkspace(any());
        verify(runner, never()).run(any(), anyList(), any());
    }

    @Test
    void workspaceNotReadyFailsExplicitlyWithoutRunning(@TempDir Path root) {
        when(workspaceService.resolve(WS_ID))
                .thenReturn(new WorkspaceService.WorkspaceResolution(true, false, root, "workspace directory not present: " + root));

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("not ready");
        verify(repositoryMapper, never()).selectByWorkspace(any());
        verify(runner, never()).run(any(), anyList(), any());
    }

    @Test
    void workspaceWithoutWorktreesFailsExplicitly(@TempDir Path root) {
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of());

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("no repository worktrees");
        verify(runner, never()).run(any(), anyList(), any());
    }

    @Test
    void pathTraversalWorktreeRejectedWithoutRunning(@TempDir Path root) {
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("../escape", "abc1234", null)));

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("escapes");
        verify(runner, never()).run(any(), anyList(), any());
    }

    @Test
    void absoluteWorktreePathRejectedWithoutRunning(@TempDir Path root) {
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID))
                .thenReturn(List.of(worktree(root.getParent().resolve("outside").toString(), "abc1234", null)));

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("escapes");
        verify(runner, never()).run(any(), anyList(), any());
    }

    @Test
    void blankWorktreePathRejectedWithoutRunning(@TempDir Path root) {
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("", "abc1234", null)));

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("escapes");
        verify(runner, never()).run(any(), anyList(), any());
    }

    @Test
    void missingWorktreeDirectoryFailsExplicitly(@TempDir Path root) {
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("repo-1", "abc1234", null)));

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("worktree directory not present");
        verify(runner, never()).run(any(), anyList(), any());
    }

    @Test
    void gitLaunchFailureReturnsFailure(@TempDir Path root) throws IOException {
        Path repoDir = Files.createDirectories(root.resolve("repo-1"));
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("repo-1", "abc1234", null)));
        when(runner.run(any(), anyList(), any())).thenAnswer(inv -> {
            List<?> argv = inv.getArgument(1);
            return argv.contains("diff")
                    ? new ExecutionResult(false, -1, "", "", "process launch failed: git not found")
                    : new ExecutionResult(true, 0, "abc1234\n", "", null);
        });

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("git diff failed to run");
    }

    @Test
    void gitNonZeroExitReturnsFailure(@TempDir Path root) throws IOException {
        Path repoDir = Files.createDirectories(root.resolve("repo-1"));
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("repo-1", "abc1234", null)));
        when(runner.run(any(), anyList(), any())).thenAnswer(inv -> {
            List<?> argv = inv.getArgument(1);
            return argv.contains("diff")
                    ? new ExecutionResult(true, 128, "", "fatal: not a git repository", null)
                    : new ExecutionResult(true, 0, "abc1234\n", "", null);
        });

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("git diff failed (exit 128)");
    }

    @Test
    void revParseFailureReturnsFailure(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("repo-1"));
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("repo-1", "abc1234", null)));
        when(runner.run(any(), anyList(), any())).thenAnswer(inv -> {
            List<?> argv = inv.getArgument(1);
            if (argv.contains("diff")) {
                return new ExecutionResult(true, 0, "", "", null);
            }
            return new ExecutionResult(true, 128, "", "fatal: ambiguous argument", null);
        });

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("git rev-parse failed");
    }

    @Test
    void invalidRevParseOutputReturnsFailure(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("repo-1"));
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("repo-1", "abc1234", null)));
        when(runner.run(any(), anyList(), any())).thenAnswer(inv -> {
            List<?> argv = inv.getArgument(1);
            if (argv.contains("diff")) {
                return new ExecutionResult(true, 0, "", "", null);
            }
            return new ExecutionResult(true, 0, "-U\n", "", null);
        });

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("invalid commit");
    }

    @Test
    void emptyDiffReturnsOkWithEmptyDiffAndRealShas(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("repo-1"));
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("repo-1", "abc1234", null)));
        when(runner.run(any(), anyList(), any())).thenAnswer(inv -> {
            List<?> argv = inv.getArgument(1);
            if (argv.contains("diff")) {
                return new ExecutionResult(true, 0, "", "", null);
            }
            return new ExecutionResult(true, 0, "0123456789abcdef0123456789abcdef01234567\n", "", null);
        });

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isTrue();
        assertThat(result.diff()).isEmpty();
        assertThat(result.baseCommit()).isEqualTo("0123456789abcdef0123456789abcdef01234567");
        assertThat(result.headCommit()).isEqualTo("0123456789abcdef0123456789abcdef01234567");
        assertThat(result.error()).isNull();
    }

    @Test
    void normalDiffReturnsDiffAndUsesHeadCommitAsBaseWhenPresent(@TempDir Path root) throws IOException {
        Path repoDir = Files.createDirectories(root.resolve("repo-1"));
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID))
                .thenReturn(List.of(worktree("repo-1", "abc1234", "1111111")));
        when(runner.run(any(), anyList(), any())).thenAnswer(inv -> {
            List<?> argv = inv.getArgument(1);
            if (argv.contains("diff")) {
                return new ExecutionResult(true, 0, "diff --git a/X.java b/X.java\n+change\n", "", null);
            }
            if (argv.contains("HEAD")) {
                return new ExecutionResult(true, 0, "2222222\n", "", null);
            }
            return new ExecutionResult(true, 0, "1111111\n", "", null);
        });

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isTrue();
        assertThat(result.diff()).contains("+change");
        assertThat(result.baseCommit()).isEqualTo("1111111");
        assertThat(result.headCommit()).isEqualTo("2222222");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(runner, atLeastOnce()).run(eq(repoDir), captor.capture(), any());
        List<String> diffArgv = captor.getAllValues().stream()
                .filter(a -> a.contains("diff"))
                .findFirst().orElseThrow();
        assertThat(diffArgv).last().isEqualTo("1111111");
    }

    @Test
    void fallsBackToBaseCommitWhenHeadCommitMissing(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("repo-1"));
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("repo-1", "abc1234", null)));
        when(runner.run(any(), anyList(), any())).thenAnswer(inv -> {
            List<?> argv = inv.getArgument(1);
            if (argv.contains("diff")) {
                return new ExecutionResult(true, 0, "", "", null);
            }
            return new ExecutionResult(true, 0, "abc1234\n", "", null);
        });

        GitDiffResult result = access().diff(WS_ID);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(runner, atLeastOnce()).run(any(), captor.capture(), any());
        List<String> diffArgv = captor.getAllValues().stream()
                .filter(a -> a.contains("diff"))
                .findFirst().orElseThrow();
        assertThat(diffArgv).last().isEqualTo("abc1234");
        assertThat(result.baseCommit()).isEqualTo("abc1234");
    }

    @Test
    void multipleWorktreesAreAggregatedWithHeaders(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("repo-1"));
        Files.createDirectories(root.resolve("repo-2"));
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID))
                .thenReturn(List.of(worktree("repo-1", "abc1234", null), worktree("repo-2", "def5678", null)));
        when(runner.run(any(), anyList(), any())).thenAnswer(inv -> {
            List<?> argv = inv.getArgument(1);
            if (argv.contains("diff")) {
                return new ExecutionResult(true, 0, "some change\n", "", null);
            }
            return new ExecutionResult(true, 0, "0123456789abcdef0123456789abcdef01234567\n", "", null);
        });

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isTrue();
        assertThat(result.diff()).contains("===== repo-1 =====");
        assertThat(result.diff()).contains("===== repo-2 =====");
        assertThat(result.diff()).contains("some change");
    }

    @Test
    void invalidBaseCommitReturnsFailure(@TempDir Path root) {
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("repo-1", "-U", null)));

        GitDiffResult result = access().diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("no valid base ref");
        verify(runner, never()).run(any(), anyList(), any());
    }

    @Test
    void gitServiceExceptionPropagatesToCaller(@TempDir Path root) {
        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> access().diff(WS_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }

    // ---------- 集成测试：真实 git ----------

    @Test
    void normalDiffWithRealGit(@TempDir Path root) throws Exception {
        assumeGitAvailable();
        SandboxProcessRunner realRunner = new SandboxProcessRunner();
        Path repoDir = Files.createDirectories(root.resolve("repo-1"));
        initGitRepo(realRunner, repoDir);
        Files.writeString(repoDir.resolve("hello.txt"), "hello");
        String baseSha = commitAll(realRunner, repoDir, "initial");
        Files.writeString(repoDir.resolve("hello.txt"), "hello world");

        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("repo-1", baseSha, null)));

        GitDiffResult result = new LocalGitDiffAccess(workspaceService, repositoryMapper, realRunner).diff(WS_ID);

        assertThat(result.ok()).as("error=%s", result.error()).isTrue();
        assertThat(result.diff()).contains("hello world");
        assertThat(result.baseCommit()).isEqualTo(baseSha);
        assertThat(result.headCommit()).isEqualTo(baseSha);
    }

    @Test
    void emptyDiffWithRealGit(@TempDir Path root) throws Exception {
        assumeGitAvailable();
        SandboxProcessRunner realRunner = new SandboxProcessRunner();
        Path repoDir = Files.createDirectories(root.resolve("repo-1"));
        initGitRepo(realRunner, repoDir);
        Files.writeString(repoDir.resolve("hello.txt"), "hello");
        String baseSha = commitAll(realRunner, repoDir, "initial");

        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("repo-1", baseSha, null)));

        GitDiffResult result = new LocalGitDiffAccess(workspaceService, repositoryMapper, realRunner).diff(WS_ID);

        assertThat(result.ok()).as("error=%s", result.error()).isTrue();
        assertThat(result.diff()).doesNotContain("@@");
        assertThat(result.baseCommit()).isEqualTo(baseSha);
    }

    @Test
    void nonGitWorkspaceFailsWithRealGit(@TempDir Path root) throws Exception {
        assumeGitAvailable();
        SandboxProcessRunner realRunner = new SandboxProcessRunner();
        Path repoDir = Files.createDirectories(root.resolve("repo-1"));
        Files.writeString(repoDir.resolve("file.txt"), "data");

        when(workspaceService.resolve(WS_ID)).thenReturn(ready(root));
        when(repositoryMapper.selectByWorkspace(WS_ID)).thenReturn(List.of(worktree("repo-1", "abc1234", null)));

        GitDiffResult result = new LocalGitDiffAccess(workspaceService, repositoryMapper, realRunner).diff(WS_ID);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("git diff failed");
    }

    // ---------- helpers ----------

    private static void assumeGitAvailable() {
        ExecutionResult probe = new SandboxProcessRunner()
                .run(Path.of("."), List.of("git", "--version"), Duration.ofSeconds(10));
        Assumptions.assumeTrue(probe.ok() && probe.exitCode() == 0, "git is not available on this machine");
    }

    private static void initGitRepo(SandboxProcessRunner runner, Path repoDir) {
        git(runner, repoDir, "init");
        git(runner, repoDir, "config", "user.email", "test@example.com");
        git(runner, repoDir, "config", "user.name", "Qgents Test");
    }

    private static String commitAll(SandboxProcessRunner runner, Path repoDir, String message) {
        git(runner, repoDir, "add", "-A");
        git(runner, repoDir, "commit", "-m", message);
        return git(runner, repoDir, "rev-parse", "HEAD").stdout().trim();
    }

    private static ExecutionResult git(SandboxProcessRunner runner, Path dir, String... args) {
        List<String> argv = new java.util.ArrayList<>(List.of("git"));
        argv.addAll(List.of(args));
        return runner.run(dir, argv, GIT_TIMEOUT);
    }
}
