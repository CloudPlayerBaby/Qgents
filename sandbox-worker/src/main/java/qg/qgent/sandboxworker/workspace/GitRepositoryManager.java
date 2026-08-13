package qg.qgent.sandboxworker.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.io.ByteArrayOutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 管理 Worker 私有共享 bare Git Store 及其 linked worktree。
 * 所有命令参数均由服务端构造，接口调用方不能传入远端地址、凭证或任意 Git 参数。
 */
@Component
@RequiredArgsConstructor
public class GitRepositoryManager {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_DIFF_BYTES = 10 * 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 1024 * 1024;
    private static final Duration PROCESS_TERMINATION_GRACE = Duration.ofSeconds(2);
    private static final Duration READER_JOIN_TIMEOUT = Duration.ofSeconds(5);
    private final SandboxWorkerProperties properties;
    private final ConcurrentMap<UUID, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    /** 从共享 bare store 创建 linked worktree，并返回真实基线和 HEAD。 */
    public WorktreeResult create(UUID repositoryId, Path target, String baseRef, String sourceBranch) {
        return locked(repositoryId, () -> createLocked(repositoryId, target, baseRef, sourceBranch));
    }

    private WorktreeResult createLocked(UUID repositoryId, Path target, String baseRef, String sourceBranch) {
        Path store = gitStore(repositoryId);
        requireStore(store);
        if (Files.exists(target)) {
            throw conflict("REPOSITORY_PATH_EXISTS", "Workspace 仓库目录已经存在");
        }
        String baseCommit = resolveCommit(store, baseRef);
        if (baseCommit == null) {
            throw invalid("GIT_BASE_REF_NOT_FOUND", "无法在共享 Git Store 中解析基线引用");
        }
        String branchRef = "refs/heads/" + sourceBranch;
        String branchCommit = resolveCommit(store, branchRef);
        List<String> command = new ArrayList<>(List.of("git", "--git-dir", store.toString(), "worktree", "add"));
        if (branchCommit == null) {
            command.addAll(List.of("-b", sourceBranch, target.toString(), baseCommit));
        } else {
            command.addAll(List.of(target.toString(), sourceBranch));
        }
        CommandResult result = run(command, Map.of());
        if (result.exitCode() != 0) {
            if (result.stderr().contains("already checked out")) {
                throw conflict("SOURCE_BRANCH_IN_USE", "功能分支已被其他 Workspace 使用");
            }
            throw invalid("WORKTREE_CREATE_FAILED", "无法从共享 Git Store 创建 linked worktree");
        }
        try {
            configureIdentity(target);
            return new WorktreeResult(baseCommit, head(target));
        } catch (RuntimeException exception) {
            run(List.of("git", "--git-dir", store.toString(), "worktree", "remove", "--force", target.toString()),
                    Map.of());
            run(List.of("git", "--git-dir", store.toString(), "worktree", "prune"), Map.of());
            throw exception;
        }
    }

    /** 从 bare store 正确注销并清理 linked worktree。 */
    public void remove(UUID repositoryId, Path target) {
        locked(repositoryId, () -> {
            Path store = gitStore(repositoryId);
            requireStore(store);
            requireSuccess(
                    run(List.of("git", "--git-dir", store.toString(), "worktree", "remove", "--force",
                            target.toString()), Map.of()),
                    "WORKTREE_REMOVE_FAILED", "无法注销 linked worktree");
            requireSuccess(run(List.of("git", "--git-dir", store.toString(), "worktree", "prune"), Map.of()),
                    "WORKTREE_PRUNE_FAILED", "无法清理 linked worktree 元数据");
            return null;
        });
    }

    /** 返回结构化工作树状态。 */
    public GitStatusResponse status(Path repository) {
        String branch = requireSuccess(
                run(List.of("git", "-C", repository.toString(), "branch", "--show-current"), Map.of()),
                "GIT_STATUS_FAILED", "无法读取当前分支").stdout().trim();
        String head = head(repository);
        String raw = requireSuccess(
                run(List.of("git", "-C", repository.toString(), "status", "--porcelain=v1", "-z",
                        "--untracked-files=all"), Map.of()),
                "GIT_STATUS_FAILED", "无法读取工作树状态").stdout();
        List<GitChangeResponse> changes = parseStatus(raw);
        return new GitStatusResponse(branch, head, changes.isEmpty(), changes);
    }

    /** 生成包含 tracked 与 untracked 文件的完整二进制 patch，并计算 SHA-256。 */
    public GitDiffResponse diff(Path repository) {
        Path index = null;
        try {
            index = Files.createTempFile("qgents-git-index-", ".tmp");
            Files.delete(index);
            Map<String, String> environment = Map.of("GIT_INDEX_FILE", index.toString());
            requireSuccess(run(List.of("git", "-C", repository.toString(), "read-tree", "HEAD"), environment),
                    "GIT_DIFF_FAILED", "无法准备 Git Diff");
            requireSuccess(run(List.of("git", "-C", repository.toString(), "add", "-A"), environment),
                    "GIT_DIFF_FAILED", "无法收集工作树变更");
            CommandResult result = requireSuccess(
                    runLimited(List.of("git", "-C", repository.toString(), "diff", "--cached",
                            "--binary", "--no-ext-diff", "--no-color", "HEAD"), environment, MAX_DIFF_BYTES),
                    "GIT_DIFF_FAILED", "无法生成 Git Diff");
            byte[] patch = result.stdout().getBytes(StandardCharsets.UTF_8);
            return new GitDiffResponse(head(repository), sha256(patch), result.stdout());
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "GIT_DIFF_FAILED", "无法生成 Git Diff");
        } finally {
            if (index != null) {
                try {
                    Files.deleteIfExists(index);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** 校验审查快照后，在内部执行 add -A 与 commit。 */
    public GitCommitResponse commit(Path repository, GitCommitRequest request) {
        String currentHead = head(repository);
        if (!currentHead.equals(request.getExpectedHeadCommit())) {
            throw conflict("GIT_HEAD_MISMATCH", "Workspace HEAD 已发生变化");
        }
        GitDiffResponse currentDiff = diff(repository);
        if (!currentDiff.getDiffHash().equals(request.getExpectedDiffHash())) {
            throw conflict("GIT_DIFF_MISMATCH", "Workspace 内容与已审查 Diff 不一致");
        }
        if (currentDiff.getPatch().isEmpty()) {
            throw conflict("GIT_NOTHING_TO_COMMIT", "Workspace 没有可提交的变更");
        }
        requireSuccess(run(List.of("git", "-C", repository.toString(), "add", "-A"), Map.of()),
                "GIT_COMMIT_FAILED", "无法暂存 Workspace 变更");
        CommandResult staged = requireSuccess(runLimited(List.of("git", "-C", repository.toString(), "diff", "--cached",
                "--binary", "--no-ext-diff", "--no-color", "HEAD"), Map.of(), MAX_DIFF_BYTES),
                "GIT_COMMIT_FAILED", "无法复核暂存区快照");
        String stagedHash = sha256(staged.stdout().getBytes(StandardCharsets.UTF_8));
        if (!stagedHash.equals(request.getExpectedDiffHash())) {
            run(List.of("git", "-C", repository.toString(), "reset", "--mixed", "HEAD"), Map.of());
            throw conflict("GIT_DIFF_MISMATCH", "git add 后的暂存区与已审查 Diff 不一致");
        }
        requireSuccess(run(List.of("git", "-C", repository.toString(), "commit", "-m", request.getMessage()), Map.of()),
                "GIT_COMMIT_FAILED", "无法创建 Git Commit");
        return new GitCommitResponse(head(repository));
    }

    /** 推送 Workspace 的 sourceBranch，并通过远端引用核验真实 SHA。 */
    public GitPushResponse push(UUID repositoryId, Path repository, String sourceBranch, GitPushRequest request) {
        return locked(repositoryId, () -> {
            String currentHead = head(repository);
            if (!currentHead.equals(request.getExpectedHeadCommit())) {
                throw conflict("GIT_HEAD_MISMATCH", "Workspace HEAD 已发生变化");
            }
            Path store = gitStore(repositoryId);
            CommandResult origin = run(List.of("git", "--git-dir", store.toString(), "remote", "get-url", "origin"),
                    Map.of());
            if (origin.exitCode() != 0) {
                throw invalid("GIT_ORIGIN_NOT_CONFIGURED", "共享 Git Store 未配置受控 origin");
            }
            requireSuccess(run(List.of("git", "--git-dir", store.toString(), "push", "origin",
                    "refs/heads/" + sourceBranch + ":refs/heads/" + sourceBranch), Map.of()),
                    "GIT_PUSH_FAILED", "Git 推送失败");
            CommandResult remote = requireSuccess(run(List.of("git", "--git-dir", store.toString(), "ls-remote",
                    "origin", "refs/heads/" + sourceBranch), Map.of()),
                    "GIT_REMOTE_VERIFY_FAILED", "无法核验远端分支");
            String remoteCommit = remote.stdout().isBlank() ? "" : remote.stdout().trim().split("\\s+")[0];
            if (!currentHead.equals(remoteCommit)) {
                throw new WorkerException(HttpStatus.BAD_GATEWAY, "GIT_REMOTE_SHA_MISMATCH", "远端分支未指向预期 Commit");
            }
            return new GitPushResponse(sourceBranch, currentHead, true);
        });
    }

    public String head(Path repository) {
        return requireSuccess(run(List.of("git", "-C", repository.toString(), "rev-parse", "HEAD"), Map.of()),
                "REPOSITORY_INVALID", "无法读取 Workspace 仓库 HEAD").stdout().trim();
    }

    private void configureIdentity(Path target) {
        requireSuccess(run(List.of("git", "-C", target.toString(), "config", "user.name", "Qgents Agent"), Map.of()),
                "REPOSITORY_CONFIG_FAILED", "无法配置 Git 提交身份");
        requireSuccess(
                run(List.of("git", "-C", target.toString(), "config", "user.email", "agent@qgents.local"), Map.of()),
                "REPOSITORY_CONFIG_FAILED", "无法配置 Git 提交身份");
    }

    private List<GitChangeResponse> parseStatus(String raw) {
        List<GitChangeResponse> changes = new ArrayList<>();
        String[] entries = raw.split("\\u0000", -1);
        for (int index = 0; index < entries.length; index++) {
            String entry = entries[index];
            if (entry.length() < 4)
                continue;
            String original = null;
            char x = entry.charAt(0);
            char y = entry.charAt(1);
            String path = entry.substring(3);
            if ((x == 'R' || x == 'C') && index + 1 < entries.length)
                original = entries[++index];
            changes.add(new GitChangeResponse(String.valueOf(x), String.valueOf(y), path, original));
        }
        return List.copyOf(changes);
    }

    private String resolveCommit(Path store, String reference) {
        CommandResult result = run(
                List.of("git", "--git-dir", store.toString(), "rev-parse", "--verify", reference + "^{commit}"),
                Map.of());
        return result.exitCode() == 0 ? result.stdout().trim() : null;
    }

    private Path gitStore(UUID repositoryId) {
        Path root = Path.of(properties.getGitStoreRoot()).toAbsolutePath().normalize();
        Path store = root.resolve(repositoryId + ".git").normalize();
        if (!store.startsWith(root))
            throw invalid("GIT_STORE_PATH_INVALID", "Git Store 路径越界");
        return store;
    }

    private void requireStore(Path store) {
        if (!Files.isDirectory(store))
            throw invalid("GIT_STORE_NOT_FOUND", "共享 Git Store 尚未准备完成");
    }

    private <T> T locked(UUID repositoryId, Supplier<T> action) {
        ReentrantLock localLock = localLocks.computeIfAbsent(repositoryId, ignored -> new ReentrantLock());
        localLock.lock();
        try {
            Path directory = Path.of(properties.getGitStoreRoot()).toAbsolutePath().normalize().resolve(".locks");
            Files.createDirectories(directory);
            try (FileChannel channel = FileChannel.open(directory.resolve(repositoryId + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
                return action.get();
            }
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "GIT_REPOSITORY_LOCK_FAILED", "无法锁定共享 Git 仓库");
        } finally {
            localLock.unlock();
        }
    }

    private CommandResult requireSuccess(CommandResult result, String code, String message) {
        if (result.exitCode() != 0)
            throw invalid(code, message);
        return result;
    }

    private CommandResult run(List<String> command, Map<String, String> environment) {
        return run(command, environment, Integer.MAX_VALUE);
    }

    private CommandResult runLimited(List<String> command, Map<String, String> environment, int maxStdoutBytes) {
        return run(command, environment, maxStdoutBytes);
    }

    private CommandResult run(List<String> command, Map<String, String> environment, int maxStdoutBytes) {
        Process process = null;
        Thread out = null;
        Thread err = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(false);
            builder.environment().putAll(environment);
            process = builder.start();
            Process running = process;
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            AtomicBoolean stdoutExceeded = new AtomicBoolean(false);
            AtomicBoolean stderrExceeded = new AtomicBoolean(false);
            out = Thread.ofVirtual().start(() -> transfer(running.getInputStream(), stdout,
                    maxStdoutBytes, stdoutExceeded, running));
            err = Thread.ofVirtual().start(() -> transfer(running.getErrorStream(), stderr,
                    MAX_STDERR_BYTES, stderrExceeded, running));
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                terminateProcessTree(process);
                closeProcessPipes(process);
                joinReaders(out, err);
                throw new WorkerException(HttpStatus.GATEWAY_TIMEOUT, "GIT_COMMAND_TIMEOUT", "Git 操作执行超时");
            }
            closeQuietly(process.getOutputStream());
            if (!joinReaders(out, err)) {
                terminateProcessTree(process);
                closeProcessPipes(process);
                joinReaders(out, err);
                throw new WorkerException(HttpStatus.BAD_GATEWAY, "GIT_READER_TIMEOUT", "Git 输出读取线程未能及时结束");
            }
            if (stdoutExceeded.get()) {
                throw new WorkerException(HttpStatus.PAYLOAD_TOO_LARGE, "GIT_DIFF_TOO_LARGE", "Git Diff 超过 10 MiB 上限");
            }
            if (stderrExceeded.get()) {
                throw new WorkerException(HttpStatus.BAD_GATEWAY, "GIT_COMMAND_OUTPUT_TOO_LARGE", "Git 错误输出超过 1 MiB 上限");
            }
            return new CommandResult(process.exitValue(), stdout.toString(StandardCharsets.UTF_8),
                    stderr.toString(StandardCharsets.UTF_8));
        } catch (WorkerException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            terminateProcessTree(process);
            closeProcessPipes(process);
            joinReadersUninterruptibly(out, err);
            Thread.currentThread().interrupt();
            throw new WorkerException(HttpStatus.BAD_GATEWAY, "GIT_COMMAND_INTERRUPTED", "Git 操作被中断");
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.BAD_GATEWAY, "GIT_COMMAND_FAILED", "无法执行受控 Git 操作");
        } finally {
            if (process != null && process.isAlive()) terminateProcessTree(process);
            closeProcessPipes(process);
            joinReadersUninterruptibly(out, err);
        }
    }

    private void transfer(java.io.InputStream input, ByteArrayOutputStream output, int limit,
            AtomicBoolean exceeded, Process process) {
        try (input; output) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() + read > limit) {
                    exceeded.set(true);
                    terminateProcessTree(process);
                    return;
                }
                output.write(buffer, 0, read);
            }
        } catch (Exception ignored) { }
    }

    static void terminateProcessTree(Process process) {
        if (process == null) return;
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        waitForExit(process.toHandle(), descendants, PROCESS_TERMINATION_GRACE);
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        waitForExit(process.toHandle(), descendants, PROCESS_TERMINATION_GRACE);
    }

    private static void waitForExit(ProcessHandle parent, List<ProcessHandle> descendants, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while ((parent.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
                && System.nanoTime() < deadline) {
            try { Thread.sleep(10); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
        }
    }

    static boolean joinReaders(Thread... readers) throws InterruptedException {
        long deadline = System.nanoTime() + READER_JOIN_TIMEOUT.toNanos();
        for (Thread reader : readers) {
            if (reader == null) continue;
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) reader.join(Duration.ofNanos(remaining));
        }
        for (Thread reader : readers) if (reader != null && reader.isAlive()) return false;
        return true;
    }

    private static void joinReadersUninterruptibly(Thread... readers) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + READER_JOIN_TIMEOUT.toNanos();
        try {
            for (Thread reader : readers) {
                if (reader == null) continue;
                while (reader.isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return;
                    try { reader.join(Duration.ofNanos(remaining)); }
                    catch (InterruptedException exception) { interrupted = true; }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void closeProcessPipes(Process process) {
        if (process == null) return;
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
    }

    private static void closeQuietly(java.io.Closeable stream) {
        if (stream == null) return;
        try { stream.close(); } catch (Exception ignored) { }
    }

    private String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private WorkerException invalid(String code, String message) {
        return new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private WorkerException conflict(String code, String message) {
        return new WorkerException(HttpStatus.CONFLICT, code, message);
    }

    public record WorktreeResult(String baseCommit, String headCommit) {
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
