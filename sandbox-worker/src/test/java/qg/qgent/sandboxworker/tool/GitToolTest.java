package qg.qgent.sandboxworker.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import qg.qgent.sandboxworker.runtime.CommandExecutionResult;
import qg.qgent.sandboxworker.runtime.CommandExecutor;
import qg.qgent.sandboxworker.runtime.SandboxAllocation;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class GitToolTest {

    private CommandExecutor executor;
    private GitTool gitTool;
    private ToolContext context;
    private SandboxAllocation sandbox;

    @BeforeEach
    void setUp() {
        executor = mock(CommandExecutor.class);
        gitTool = new GitTool(executor);
        sandbox = mock(SandboxAllocation.class);
        context = mock(ToolContext.class);
        when(context.getSandbox()).thenReturn(sandbox);
        when(context.getContainerRepository()).thenReturn("/container");
        when(context.getTimeout()).thenReturn(Duration.ofSeconds(10));
    }

    private void mockCommand(List<String> command, int exitCode, List<String> stdout) throws InterruptedException {
        when(executor.execute(eq(sandbox), any(), eq(command), any()))
                .thenReturn(new CommandExecutionResult(exitCode, stdout, List.of()));
    }

    private void mockCommandError(List<String> command, int exitCode, List<String> stderr) throws InterruptedException {
        when(executor.execute(eq(sandbox), any(), eq(command), any()))
                .thenReturn(new CommandExecutionResult(exitCode, List.of(), stderr));
    }

    @Test
    void testGitHeadSuccess() throws InterruptedException {
        mockCommand(List.of("git", "rev-parse", "HEAD"), 0, List.of("abcdef123456"));
        mockCommand(List.of("git", "symbolic-ref", "--short", "HEAD"), 0, List.of("feat/test-branch"));
        mockCommand(List.of("git", "status", "--porcelain"), 0, List.of());

        ToolResult result = gitTool.execute("git.head", context, Map.of());

        assertEquals(0, result.getExitCode());
        assertEquals("abcdef123456", result.getResult().get("headCommit"));
        assertEquals("feat/test-branch", result.getResult().get("branch"));
        assertEquals(true, result.getResult().get("workingTreeClean"));
    }

    @Test
    void testGitHeadDetached() throws InterruptedException {
        mockCommand(List.of("git", "rev-parse", "HEAD"), 0, List.of("abcdef123456"));
        // symbolic-ref returns error when detached
        mockCommandError(List.of("git", "symbolic-ref", "--short", "HEAD"), 128, List.of("fatal: ref HEAD is not a symbolic ref"));
        mockCommand(List.of("git", "status", "--porcelain"), 0, List.of("M  file.txt"));

        ToolResult result = gitTool.execute("git.head", context, Map.of());

        assertEquals(0, result.getExitCode());
        assertEquals("abcdef123456", result.getResult().get("headCommit"));
        assertEquals("HEAD", result.getResult().get("branch"));
        assertEquals(false, result.getResult().get("workingTreeClean"));
    }

    @Test
    void testGitHeadNonRepo() throws InterruptedException {
        mockCommandError(List.of("git", "rev-parse", "HEAD"), 128, List.of("fatal: not a git repository"));

        ToolResult result = gitTool.execute("git.head", context, Map.of());

        assertEquals(128, result.getExitCode());
        assertEquals("Failed to resolve HEAD", result.getResult().get("error"));
    }

    @Test
    void testGitCommitSuccess() throws InterruptedException {
        mockCommand(List.of("git", "commit", "-m", "fix typo"), 0, List.of("[main 123456] fix typo"));
        mockCommand(List.of("git", "rev-parse", "HEAD"), 0, List.of("123456"));
        mockCommand(List.of("git", "symbolic-ref", "--short", "HEAD"), 0, List.of("main"));
        mockCommand(List.of("git", "status", "--porcelain"), 0, List.of());

        ToolResult result = gitTool.execute("git.commit", context, Map.of("message", "fix typo"));

        assertEquals(0, result.getExitCode());
        assertEquals("123456", result.getResult().get("commitSha"));
        assertEquals("main", result.getResult().get("branch"));
        assertEquals(true, result.getResult().get("workingTreeClean"));
    }

    @Test
    void testGitCommitFails() throws InterruptedException {
        mockCommandError(List.of("git", "commit", "-m", "fail"), 1, List.of("nothing to commit"));

        ToolResult result = gitTool.execute("git.commit", context, Map.of("message", "fail"));

        assertEquals(1, result.getExitCode());
        assertNull(result.getResult().get("commitSha")); // No commitSha forged
    }

    @Test
    void testGitPushSuccess() throws InterruptedException {
        String sha = "abc123def456";
        mockCommand(List.of("git", "rev-parse", "HEAD"), 0, List.of(sha));
        mockCommand(List.of("git", "push", "origin", "main"), 0, List.of("Everything up-to-date"));
        mockCommand(List.of("git", "ls-remote", "origin", "refs/heads/main"), 0, List.of(sha + "\trefs/heads/main"));

        ToolResult result = gitTool.execute("git.push", context, Map.of(
                "expectedHeadCommit", sha,
                "remote", "origin",
                "branch", "main"
        ));

        assertEquals(0, result.getExitCode());
        assertEquals(true, result.getResult().get("pushed"));
    }

    @Test
    void testGitPushMismatchedHead() throws InterruptedException {
        mockCommand(List.of("git", "rev-parse", "HEAD"), 0, List.of("different_sha"));

        ToolResult result = gitTool.execute("git.push", context, Map.of(
                "expectedHeadCommit", "abc123def456",
                "remote", "origin",
                "branch", "main"
        ));

        assertEquals(1, result.getExitCode());
        assertEquals(false, result.getResult().get("pushed"));
        assertEquals("HEAD_MISMATCH", result.getResult().get("reason"));
        // Push should not be called
        verify(executor, never()).execute(any(), any(), eq(List.of("git", "push", "origin", "main")), any());
    }

    @Test
    void testGitPushInvalidRemote() throws InterruptedException {
        ToolResult result = gitTool.execute("git.push", context, Map.of(
                "expectedHeadCommit", "sha",
                "remote", "upstream",
                "branch", "main"
        ));

        assertEquals(1, result.getExitCode());
        assertEquals("Remote must be origin", result.getResult().get("error"));
    }

    @Test
    void testGitPushInvalidBranch() throws InterruptedException {
        ToolResult result = gitTool.execute("git.push", context, Map.of(
                "expectedHeadCommit", "sha",
                "remote", "origin",
                "branch", "main --force"
        ));

        assertEquals(1, result.getExitCode());
        assertEquals("Invalid branch name format", result.getResult().get("error"));
    }

    @Test
    void testGitPushCommandFails() throws InterruptedException {
        String sha = "abc123def456";
        mockCommand(List.of("git", "rev-parse", "HEAD"), 0, List.of(sha));
        mockCommandError(List.of("git", "push", "origin", "main"), 128, List.of("fatal: unable to access"));

        ToolResult result = gitTool.execute("git.push", context, Map.of(
                "expectedHeadCommit", sha,
                "remote", "origin",
                "branch", "main"
        ));

        assertEquals(128, result.getExitCode());
        assertEquals(false, result.getResult().get("pushed"));
        assertEquals("PUSH_FAILED", result.getResult().get("reason"));
    }

    @Test
    void testGitPushRemoteShaMismatch() throws InterruptedException {
        String sha = "abc123def456";
        mockCommand(List.of("git", "rev-parse", "HEAD"), 0, List.of(sha));
        mockCommand(List.of("git", "push", "origin", "main"), 0, List.of());
        mockCommand(List.of("git", "ls-remote", "origin", "refs/heads/main"), 0, List.of("wrongsha123\trefs/heads/main"));

        ToolResult result = gitTool.execute("git.push", context, Map.of(
                "expectedHeadCommit", sha,
                "remote", "origin",
                "branch", "main"
        ));

        assertEquals(1, result.getExitCode());
        assertEquals(false, result.getResult().get("pushed"));
        assertEquals("REMOTE_SHA_MISMATCH", result.getResult().get("reason"));
    }
}
