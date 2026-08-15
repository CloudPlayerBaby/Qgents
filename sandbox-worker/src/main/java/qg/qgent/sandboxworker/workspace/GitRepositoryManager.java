package qg.qgent.sandboxworker.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;
import qg.qgent.sandboxworker.api.MergePreviewResponse;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 管理 Worker 专属的共享 bare Git Store 及其 linked worktree。
 * 所有命令参数均由服务端构造，调用方不能传入远程地址、凭据或任意 Git 参数。
 */
@Component
public class GitRepositoryManager {
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_DIFF_BYTES = 10 * 1024 * 1024;
    private static final int MAX_STDERR_BYTES = 1024 * 1024;
    private static final Duration PROCESS_TERMINATION_GRACE = Duration.ofSeconds(2);
    private static final Duration READER_JOIN_TIMEOUT = Duration.ofSeconds(5);
    private final SandboxWorkerProperties properties;
    private org.springframework.web.client.RestClient restClient = org.springframework.web.client.RestClient.create();
    private final ConcurrentMap<UUID, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public GitRepositoryManager(SandboxWorkerProperties properties) {
        this.properties = properties;
    }

    // For testing
    GitRepositoryManager(SandboxWorkerProperties properties, org.springframework.web.client.RestClient restClient) {
        this.properties = properties;
        this.restClient = restClient;
    }

    /** 从共享 bare store 创建 linked worktree，并返回实际基线和 HEAD。 */
    public WorktreeResult create(UUID repositoryId, Path target, String baseRef, String sourceBranch) {
        return locked(repositoryId, () -> createLocked(repositoryId, target, baseRef, sourceBranch));
    }

    private WorktreeResult createLocked(UUID repositoryId, Path target, String baseRef, String sourceBranch) {
        Path store = gitStore(repositoryId);
        requireStore(store);
        if (Files.exists(target)) {
            throw conflict("REPOSITORY_PATH_EXISTS", "Workspace 目录已经存在");
        }
        String baseCommit = resolveCommit(store, baseRef);
        if (baseCommit == null) {
            throw invalid("GIT_BASE_REF_NOT_FOUND", "Git base reference not found");
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
            throw invalid("WORKTREE_CREATE_FAILED", "Cannot create linked worktree");
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
            if (Files.exists(target)) {
                requireSuccess(
                        run(List.of("git", "--git-dir", store.toString(), "worktree", "remove", "--force",
                                target.toString()), Map.of()),
                        "WORKTREE_REMOVE_FAILED", "无法注销 linked worktree");
            }
            requireSuccess(run(List.of("git", "--git-dir", store.toString(), "worktree", "prune"), Map.of()),
                "WORKTREE_PRUNE_FAILED", "Cannot prune linked worktree metadata");
            return null;
        });
    }

    /** 把源 worktree 的完整未提交 patch 应用到相同 HEAD 的隔离 worktree，并校验快照哈希。 */
    public void copyWorkingTreeSnapshot(UUID repositoryId, Path source, Path target) {
        locked(repositoryId, () -> {
            if (!head(source).equals(head(target))) {
                throw conflict("TEST_SNAPSHOT_HEAD_MISMATCH", "源工作树与隔离工作树 HEAD 不一致");
            }
            GitDiffResponse sourceDiff = diff(source);
            if (sourceDiff.getPatch() == null || sourceDiff.getPatch().isEmpty()) return null;
            Path patch = null;
            try {
                patch = Files.createTempFile("qgents-test-snapshot-", ".patch");
                Files.writeString(patch, sourceDiff.getPatch(), StandardCharsets.UTF_8,
                        StandardOpenOption.TRUNCATE_EXISTING);
                requireSuccess(run(List.of("git", "-C", target.toString(), "apply", "--binary",
                                "--whitespace=nowarn", patch.toString()), Map.of()),
                        "TEST_SNAPSHOT_APPLY_FAILED", "无法创建隔离工作树快照");
                GitDiffResponse copied = diff(target);
                if (!sourceDiff.getDiffHash().equals(copied.getDiffHash())) {
                    throw conflict("TEST_SNAPSHOT_HASH_MISMATCH", "隔离工作树与源工作树快照不一致");
                }
                return null;
            } catch (WorkerException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "TEST_SNAPSHOT_CREATE_FAILED",
                        "无法创建隔离工作树快照");
            } finally {
                if (patch != null) try { Files.deleteIfExists(patch); } catch (Exception ignored) { }
            }
        });
    }

    /** 返回结构化的工作树状态。 */
    public GitStatusResponse status(Path repository) {
        String branch = requireSuccess(
                run(List.of("git", "-C", repository.toString(), "branch", "--show-current"), Map.of()),
                "GIT_STATUS_FAILED", "Cannot read current branch").stdout().trim();
        String head = head(repository);
        String raw = requireSuccess(
                run(List.of("git", "-C", repository.toString(), "status", "--porcelain=v1", "-z",
                        "--untracked-files=all"), Map.of()),
                "GIT_STATUS_FAILED", "Cannot read worktree status").stdout();
        List<GitChangeResponse> changes = parseStatus(raw);
        return new GitStatusResponse(branch, head, changes.isEmpty(), changes);
    }

    /** 生成包含 tracked 和 untracked 文件的完整二进制 patch，并计算 SHA-256。 */
    public GitDiffResponse diff(Path repository) {
        Path index = null;
        try {
            index = Files.createTempFile("qgents-git-index-", ".tmp");
            Files.delete(index);
            Map<String, String> environment = Map.of("GIT_INDEX_FILE", index.toString());
            requireSuccess(run(List.of("git", "-C", repository.toString(), "read-tree", "HEAD"), environment),
                    "GIT_DIFF_FAILED", "Cannot prepare Git diff");
            requireSuccess(run(List.of("git", "-C", repository.toString(), "add", "-A"), environment),
                    "GIT_DIFF_FAILED", "Cannot collect worktree changes");
            CommandResult result = requireSuccess(
                    runLimited(List.of("git", "-C", repository.toString(), "diff", "--cached",
                            "--binary", "--no-ext-diff", "--no-color", "HEAD"), environment, MAX_DIFF_BYTES),
                    "GIT_DIFF_FAILED", "Cannot generate Git diff");
            byte[] patch = result.stdout().getBytes(StandardCharsets.UTF_8);
            return new GitDiffResponse(null, head(repository), sha256(patch), result.stdout(),
                    diffFiles(repository, environment));
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "GIT_DIFF_FAILED", "Cannot generate Git diff");
        } finally {
            if (index != null) {
                try {
                    Files.deleteIfExists(index);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** Read file statistics from Git's machine-readable numstat output, never from patch text. */
    private List<GitDiffFileResponse> diffFiles(Path repository, Map<String, String> environment) {
        CommandResult names = requireSuccess(run(List.of("git", "-C", repository.toString(), "diff", "--cached",
                "--name-status", "-z", "--find-renames", "--no-ext-diff", "HEAD"), environment),
                "GIT_DIFF_FAILED", "Cannot read Git diff file names");
        Map<String, FileNameStatus> statusByPath = parseNameStatus(names.stdout());
        CommandResult numstat = requireSuccess(run(List.of("git", "-C", repository.toString(), "diff", "--cached",
                "--numstat", "-z", "--no-ext-diff", "HEAD"), environment), "GIT_DIFF_FAILED",
                "Cannot read Git diff file statistics");
        List<GitDiffFileResponse> files = new ArrayList<>();
        String[] entries = numstat.stdout().split("\u0000", -1);
        for (int index = 0; index < entries.length; index++) {
            String entry = entries[index];
            if (entry.isEmpty()) {
                continue;
            }
            String[] parts = entry.split("\\t", 3);
            if (parts.length != 3) {
                throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "GIT_DIFF_FAILED",
                        "Unexpected git numstat output");
            }
            boolean binary = "-".equals(parts[0]) || "-".equals(parts[1]);
            int additions = binary ? 0 : Integer.parseInt(parts[0]);
            int deletions = binary ? 0 : Integer.parseInt(parts[1]);
            String path = parts[2];
            String previousPath = null;
            if (path.isEmpty()) {
                if (index + 2 >= entries.length) {
                    throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "GIT_DIFF_FAILED",
                            "Unexpected git rename numstat output");
                }
                previousPath = entries[++index];
                path = entries[++index];
            }
            FileNameStatus nameStatus = statusByPath.get(path);
            files.add(new GitDiffFileResponse(path, nameStatus == null ? previousPath : nameStatus.previousPath(),
                    nameStatus == null ? "MODIFIED" : nameStatus.changeType(), additions, deletions, binary, List.of()));
        }
        return files;
    }

    /** Parses Git's NUL-delimited name-status format, including two-path rename records. */
    private Map<String, FileNameStatus> parseNameStatus(String raw) {
        Map<String, FileNameStatus> result = new LinkedHashMap<>();
        String[] entries = raw.split("\u0000", -1);
        for (int index = 0; index < entries.length; index++) {
            String status = entries[index];
            if (status.isEmpty()) {
                continue;
            }
            if (index + 1 >= entries.length) {
                throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "GIT_DIFF_FAILED",
                        "Unexpected git name-status output");
            }
            String path = entries[++index];
            String previousPath = null;
            if (status.startsWith("R") || status.startsWith("C")) {
                previousPath = path;
                if (index + 1 >= entries.length) {
                    throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "GIT_DIFF_FAILED",
                            "Unexpected git rename name-status output");
                }
                path = entries[++index];
            }
            result.put(path, new FileNameStatus(changeType(status), previousPath));
        }
        return result;
    }

    private String changeType(String status) {
        return switch (status.charAt(0)) {
            case 'A' -> "ADDED";
            case 'D' -> "DELETED";
            case 'R' -> "RENAMED";
            case 'C' -> "COPIED";
            default -> "MODIFIED";
        };
    }

    private record FileNameStatus(String changeType, String previousPath) {
    }

    /** 校验审查快照后，在内部执行 add -A 和 commit。 */
    public GitCommitResponse commit(Path repository, GitCommitRequest request) {
        String currentHead = head(repository);
        if (!currentHead.equals(request.getExpectedHeadCommit())) {
            String message = requireSuccess(run(List.of("git", "-C", repository.toString(), "log", "-1",
                    "--format=%B", currentHead), Map.of()), "GIT_COMMIT_READ_FAILED",
                    "Cannot inspect existing commit").stdout();
            if (message.lines().anyMatch(line -> line.equals("Qgents-Operation-Id: " + request.getOperationId()))) {
                return new GitCommitResponse(currentHead);
            }
            throw conflict("GIT_HEAD_MISMATCH", "Workspace HEAD has changed");
        }
        GitDiffResponse currentDiff = diff(repository);
        if (!currentDiff.getDiffHash().equals(request.getExpectedDiffHash())) {
            throw conflict("GIT_DIFF_MISMATCH", "Workspace differs from reviewed diff");
        }
        if (currentDiff.getPatch().isEmpty()) {
            throw conflict("GIT_NOTHING_TO_COMMIT", "Workspace has no changes to commit");
        }
        requireSuccess(run(List.of("git", "-C", repository.toString(), "add", "-A"), Map.of()),
                "GIT_COMMIT_FAILED", "Cannot stage Workspace changes");
        CommandResult staged = requireSuccess(runLimited(List.of("git", "-C", repository.toString(), "diff", "--cached",
                "--binary", "--no-ext-diff", "--no-color", "HEAD"), Map.of(), MAX_DIFF_BYTES),
                "GIT_COMMIT_FAILED", "Cannot verify staged snapshot");
        String stagedHash = sha256(staged.stdout().getBytes(StandardCharsets.UTF_8));
        if (!stagedHash.equals(request.getExpectedDiffHash())) {
            run(List.of("git", "-C", repository.toString(), "reset", "--mixed", "HEAD"), Map.of());
            throw conflict("GIT_DIFF_MISMATCH", "Staged diff differs from reviewed diff");
        }
        String commitMessage = request.getMessage() + "\n\nQgents-Operation-Id: " + request.getOperationId();
        requireSuccess(run(List.of("git", "-C", repository.toString(), "commit", "-m", commitMessage), Map.of()),
                "GIT_COMMIT_FAILED", "Cannot create Git commit");
        return new GitCommitResponse(head(repository));
    }

    /** 推送 Workspace 的 sourceBranch，并通过远程引用校验实际 SHA。 */
    public GitPushResponse push(UUID repositoryId, Path repository, String sourceBranch, GitPushRequest request) {
        return locked(repositoryId, () -> {
            String currentHead = head(repository);
            if (!currentHead.equals(request.getExpectedHeadCommit())) {
                throw conflict("GIT_HEAD_MISMATCH", "Workspace HEAD has changed");
            }
            Path store = gitStore(repositoryId);
            CommandResult origin = run(List.of("git", "--git-dir", store.toString(), "remote", "get-url", "origin"),
                    Map.of());
            if (origin.exitCode() != 0) {
                throw invalid("GIT_ORIGIN_NOT_CONFIGURED", "Controlled Git origin is not configured");
            }
            
            String repositoryFullName = repositoryFullNameFromOrigin(origin.stdout().trim());
            String token = exchangeCredential(request.getCredentialGrantId(), currentHead, repositoryFullName, sourceBranch, "PUSH");
            Path askpassScript = null;
            try {
                askpassScript = createAskpassScript();
                Map<String, String> env = Map.of(
                        "GIT_ASKPASS", askpassScript.toAbsolutePath().toString(),
                        "GIT_TERMINAL_PROMPT", "0",
                        "QGENTS_GIT_TOKEN", token
                );

                requireSuccess(run(List.of("git", "--git-dir", store.toString(), "push", "origin",
                        "refs/heads/" + sourceBranch + ":refs/heads/" + sourceBranch), env),
                        "GIT_PUSH_FAILED", "Git push failed");
                CommandResult remote = requireSuccess(run(List.of("git", "--git-dir", store.toString(), "ls-remote",
                        "origin", "refs/heads/" + sourceBranch), env), "GIT_REMOTE_VERIFY_FAILED", "Remote verify failed");
                String remoteCommit = remote.stdout().isBlank() ? "" : remote.stdout().trim().split("\\s+")[0];
                if (!currentHead.equals(remoteCommit)) {
                    throw new WorkerException(HttpStatus.BAD_GATEWAY, "GIT_REMOTE_SHA_MISMATCH", "Remote SHA mismatch");
                }
            } finally {
                if (askpassScript != null) {
                    try {
                        Files.deleteIfExists(askpassScript);
                    } catch (Exception ignored) {}
                }
                token = null;
            }

            /* Command result is already verified while the short-lived credential is still active. */
            return new GitPushResponse(sourceBranch, currentHead, true);
        });
    }

    String exchangeCredential(String grantId, String headCommit, String repositoryFullName, String branchName, String purpose) {
        try {
            Map<String, String> body = Map.of(
                    "credentialGrantId", grantId,
                    "expectedHeadCommit", headCommit,
                    "repositoryFullName", repositoryFullName,
                    "branchName", branchName,
                    "purpose", purpose
            );
            
            String url = properties.getBackendUrl();
            if (!url.endsWith("/")) url += "/";
            url += "internal/v1/git-credentials/exchange";

            @SuppressWarnings("unchecked")
            Map<String, String> response = this.restClient.post()
                    .uri(url)
                    .header("Authorization", "Bearer " + properties.getBackendServiceToken())
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            
            if (response == null || !response.containsKey("token")) {
                throw invalid("CREDENTIAL_EXCHANGE_FAILED", "Credential exchange did not return a token");
            }
            return response.get("token");
        } catch (WorkerException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("CREDENTIAL_EXCHANGE_ERROR", "Credential exchange request failed");
        }
    }

    /**
     * 仅在受控 Git 命令运行期间通过 AskPass 提供短期凭据。
     * Token 不会写入磁盘，调用结束后临时启动器立即删除。
     */
    <T> T withCredential(String grantId, String headCommit, String repositoryFullName, String branchName, String purpose,
            java.util.function.Function<Map<String, String>, T> action) {
        String token = exchangeCredential(grantId, headCommit, repositoryFullName, branchName, purpose);
        Path askpassScript = null;
        try {
            askpassScript = createAskpassScript();
            return action.apply(Map.of(
                    "GIT_ASKPASS", askpassScript.toAbsolutePath().toString(),
                    "GIT_TERMINAL_PROMPT", "0",
                    "QGENTS_GIT_TOKEN", token));
        } finally {
            if (askpassScript != null) {
                try {
                    Files.deleteIfExists(askpassScript);
                } catch (Exception ignored) {
                }
            }
            token = null;
        }
    }

    /** 创建一次性 AskPass 启动器；真实 Token 仅通过子进程环境变量提供，不写入临时文件。 */
    Path createAskpassScript() {
        try {
            Path script = Files.createTempFile("git-askpass-", ".sh");
            String content = "#!/bin/sh\ncase \"$1\" in\n  *Username*) printf '%s\\n' 'x-access-token' ;;\n  *) printf '%s\\n' \"${QGENTS_GIT_TOKEN:-}\" ;;\nesac\n";
            Files.writeString(script, content, StandardOpenOption.TRUNCATE_EXISTING);
            
            // chmod 700
            java.nio.file.attribute.PosixFilePermission ownerRead = java.nio.file.attribute.PosixFilePermission.OWNER_READ;
            java.nio.file.attribute.PosixFilePermission ownerWrite = java.nio.file.attribute.PosixFilePermission.OWNER_WRITE;
            java.nio.file.attribute.PosixFilePermission ownerExecute = java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE;
            try {
                Files.setPosixFilePermissions(script, java.util.Set.of(ownerRead, ownerWrite, ownerExecute));
            } catch (UnsupportedOperationException e) {
                // Windows fallback
                script.toFile().setExecutable(true, true);
                script.toFile().setReadable(true, true);
                script.toFile().setWritable(true, true);
            }
            
            return script;
        } catch (Exception e) {
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "ASKPASS_CREATION_FAILED", "Cannot create GIT_ASKPASS script");
        }
    }

    public String head(Path repository) {
        return requireSuccess(run(List.of("git", "-C", repository.toString(), "rev-parse", "HEAD"), Map.of()),
                "REPOSITORY_INVALID", "Cannot read Workspace repository HEAD").stdout().trim();
    }

    /** 使用 git merge-tree 做只读预演，并只返回结构化冲突路径。 */
    public MergePreviewResponse mergePreview(UUID repositoryId, String sourceRef, String targetBranch) {
        return locked(repositoryId, () -> {
            Path store = gitStore(repositoryId);
            requireStore(store);
            String source = resolveCommit(store, sourceRef);
            String target = resolveCommit(store, targetBranch);
            if (source == null) throw invalid("GIT_SOURCE_REF_NOT_FOUND", "Source ref was not found");
            if (target == null) throw invalid("GIT_TARGET_REF_NOT_FOUND", "Target branch was not found");
            CommandResult result = run(List.of("git", "--git-dir", store.toString(), "merge-tree", "--write-tree",
                    target, source), Map.of());
            if (result.exitCode() != 0 && result.exitCode() != 1) {
                throw invalid("GIT_MERGE_PREVIEW_FAILED", "Git merge preview failed");
            }
            List<String> conflicts = result.stdout().lines()
                    .filter(line -> line.matches("^[0-9]+ [0-9a-f]+ [123]\\t.*$"))
                    .map(line -> line.substring(line.indexOf('\t') + 1)).distinct().sorted().toList();
            boolean mergeable = result.exitCode() == 0 && conflicts.isEmpty();
            return new MergePreviewResponse(source, target, mergeable, conflicts);
        });
    }

    /** 在共享 Git Store 中解析引用并固定为 commit SHA。 */
    public String resolveRef(UUID repositoryId, String reference) {
        return locked(repositoryId, () -> {
            Path store = gitStore(repositoryId);
            requireStore(store);
            String commit = resolveCommit(store, reference);
            if (commit == null) throw invalid("GIT_REF_NOT_FOUND", "Git reference was not found");
            return commit;
        });
    }

    /** 在一次性 worktree 中合并受控源引用，供 DryRun 在真实合并树上执行门禁。 */
    public String mergeForTest(UUID repositoryId, Path repository, String sourceRef) {
        return locked(repositoryId, () -> {
            Path store = gitStore(repositoryId);
            requireStore(store);
            String source = resolveCommit(store, sourceRef);
            if (source == null) throw invalid("GIT_SOURCE_REF_NOT_FOUND", "Source ref was not found");
            CommandResult result = run(List.of("git", "-C", repository.toString(), "merge", "--no-commit",
                    "--no-ff", source), Map.of());
            if (result.exitCode() != 0) {
                run(List.of("git", "-C", repository.toString(), "merge", "--abort"), Map.of());
                throw conflict("GIT_MERGE_CONFLICT", "Merge result contains conflicts");
            }
            return source;
        });
    }

    /** 删除已经移除 worktree 的内部临时测试分支。 */
    public void deleteTemporaryBranch(UUID repositoryId, String branch) {
        if (branch == null || !branch.matches("qgents-test-[0-9a-fA-F-]{36}")) {
            throw invalid("TEMPORARY_BRANCH_INVALID", "Temporary branch name is invalid");
        }
        locked(repositoryId, () -> {
            Path store = gitStore(repositoryId);
            requireStore(store);
            if (resolveCommit(store, "refs/heads/" + branch) == null) return null;
            requireSuccess(run(List.of("git", "--git-dir", store.toString(), "branch", "-D", branch), Map.of()),
                    "TEMPORARY_BRANCH_DELETE_FAILED", "Cannot delete temporary test branch");
            return null;
        });
    }

    private void configureIdentity(Path target) {
        requireSuccess(run(List.of("git", "-C", target.toString(), "config", "user.name", "Qgents Agent"), Map.of()),
                "REPOSITORY_CONFIG_FAILED", "Cannot configure Git commit identity");
        requireSuccess(
                run(List.of("git", "-C", target.toString(), "config", "user.email", "agent@qgents.local"), Map.of()),
                "REPOSITORY_CONFIG_FAILED", "Cannot configure Git commit identity");
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

    private String repositoryFullNameFromOrigin(String originUrl) {
        try {
            java.net.URI uri = java.net.URI.create(originUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !"github.com".equalsIgnoreCase(uri.getHost()) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                throw invalid("GIT_ORIGIN_INVALID", "Git origin is not a controlled GitHub URL");
            }
            String[] segments = uri.getPath().split("/");
            if (segments.length != 3 || segments[1].isBlank() || segments[2].isBlank()) {
                throw invalid("GIT_ORIGIN_INVALID", "Git origin repository path is invalid");
            }
            String repository = segments[2].endsWith(".git") ? segments[2].substring(0, segments[2].length() - 4) : segments[2];
            if (!segments[1].matches("[A-Za-z0-9_.-]+") || !repository.matches("[A-Za-z0-9_.-]+")) {
                throw invalid("GIT_ORIGIN_INVALID", "Git origin repository path is invalid");
            }
            return segments[1] + "/" + repository;
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("GIT_ORIGIN_INVALID", "Git origin is invalid");
        }
    }

    Path gitStore(UUID repositoryId) {
        Path root = Path.of(properties.getGitStoreRoot()).toAbsolutePath().normalize();
        Path store = root.resolve(repositoryId + ".git").normalize();
        if (!store.startsWith(root))
            throw invalid("GIT_STORE_PATH_INVALID", "Git Store path escapes its root");
        return store;
    }

    void requireStore(Path store) {
        if (!Files.isDirectory(store))
            throw invalid("GIT_STORE_NOT_FOUND", "Shared Git Store is not ready");
    }

    <T> T locked(UUID repositoryId, Supplier<T> action) {
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
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "GIT_REPOSITORY_LOCK_FAILED", "Cannot lock shared Git repository");
        } finally {
            localLock.unlock();
        }
    }

    CommandResult requireSuccess(CommandResult result, String code, String message) {
        if (result.exitCode() != 0)
            throw invalid(code, message);
        return result;
    }

    CommandResult run(List<String> command, Map<String, String> environment) {
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
                throw new WorkerException(HttpStatus.GATEWAY_TIMEOUT, "GIT_COMMAND_TIMEOUT", "Git operation timed out");
            }
            closeQuietly(process.getOutputStream());
            if (!joinReaders(out, err)) {
                terminateProcessTree(process);
                closeProcessPipes(process);
                joinReaders(out, err);
                throw new WorkerException(HttpStatus.BAD_GATEWAY, "GIT_READER_TIMEOUT", "Git output reader timed out");
            }
            if (stdoutExceeded.get()) {
                throw new WorkerException(HttpStatus.PAYLOAD_TOO_LARGE, "GIT_DIFF_TOO_LARGE", "Git Diff exceeds 10 MiB");
            }
            if (stderrExceeded.get()) {
                throw new WorkerException(HttpStatus.BAD_GATEWAY, "GIT_COMMAND_OUTPUT_TOO_LARGE",
                        "Git error output exceeds 1 MiB");
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
            throw new WorkerException(HttpStatus.BAD_GATEWAY, "GIT_COMMAND_INTERRUPTED", "Git operation was interrupted");
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.BAD_GATEWAY, "GIT_COMMAND_FAILED", "Cannot execute controlled Git operation");
        } finally {
            if (process != null && process.isAlive())
                terminateProcessTree(process);
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
        } catch (Exception ignored) {
        }
    }

    static void terminateProcessTree(Process process) {
        if (process == null)
            return;
        List<ProcessHandle> descendants = process.descendants().toList();
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        waitForExit(process.toHandle(), descendants, PROCESS_TERMINATION_GRACE);
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (process.isAlive())
            process.destroyForcibly();
        waitForExit(process.toHandle(), descendants, PROCESS_TERMINATION_GRACE);
    }

    private static void waitForExit(ProcessHandle parent, List<ProcessHandle> descendants, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while ((parent.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static boolean joinReaders(Thread... readers) throws InterruptedException {
        long deadline = System.nanoTime() + READER_JOIN_TIMEOUT.toNanos();
        for (Thread reader : readers) {
            if (reader == null)
                continue;
            long remaining = deadline - System.nanoTime();
            if (remaining > 0)
                reader.join(Duration.ofNanos(remaining));
        }
        for (Thread reader : readers)
            if (reader != null && reader.isAlive())
                return false;
        return true;
    }

    private static void joinReadersUninterruptibly(Thread... readers) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + READER_JOIN_TIMEOUT.toNanos();
        try {
            for (Thread reader : readers) {
                if (reader == null)
                    continue;
                while (reader.isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0)
                        return;
                    try {
                        reader.join(Duration.ofNanos(remaining));
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
            }
        } finally {
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    private static void closeProcessPipes(Process process) {
        if (process == null)
            return;
        closeQuietly(process.getOutputStream());
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
    }

    private static void closeQuietly(java.io.Closeable stream) {
        if (stream == null)
            return;
        try {
            stream.close();
        } catch (Exception ignored) {
        }
    }

    private String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    WorkerException invalid(String code, String message) {
        return new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private WorkerException conflict(String code, String message) {
        return new WorkerException(HttpStatus.CONFLICT, code, message);
    }

    public record WorktreeResult(String baseCommit, String headCommit) {
    }

    record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
