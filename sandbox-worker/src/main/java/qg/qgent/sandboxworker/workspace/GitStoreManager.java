package qg.qgent.sandboxworker.workspace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandboxworker.api.WorkerException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 管理 Worker 私有的 bare Git Store。
 * 远程地址只能由受信任的主后端根据项目已绑定仓库生成，Worker 仅接受严格校验后的 GitHub HTTPS 地址。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitStoreManager {
    private static final String GITHUB_HOST = "github.com";
    private static final String BRANCH_PATTERN = "[A-Za-z0-9][A-Za-z0-9._/-]{0,255}";
    private static final String REPOSITORY_PATH_PATTERN = "/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\\.git)?";

    private final GitRepositoryManager repositories;

    /**
     * 幂等地初始化 bare Store、覆盖为受控 origin 并同步指定远程分支。
     * 与 worktree 创建和 push 使用同一仓库锁，避免 Store 在同步过程中被并发读写。
     */
    public GitStoreSyncResponse sync(UUID repositoryId, GitStoreSyncRequest request) {
        String repositoryUrl = validateRepositoryUrl(request.getRepositoryUrl());
        validateBranch(request.getRemoteBranch());
        String repositoryFullName = repositoryFullName(repositoryUrl);
        log.info("git store sync start repositoryId={} repository={} branch={} expectedHeadCommit={} grantPresent={}",
                repositoryId, repositoryFullName, request.getRemoteBranch(), request.getExpectedHeadCommit(),
                request.getCredentialGrantId() != null && !request.getCredentialGrantId().isBlank());
        return repositories.locked(repositoryId,
                () -> syncLocked(repositoryId, request, repositoryUrl, repositoryFullName));
    }

    private GitStoreSyncResponse syncLocked(UUID repositoryId, GitStoreSyncRequest request, String repositoryUrl,
                                            String repositoryFullName) {
        Path store = repositories.gitStore(repositoryId);
        boolean created = ensureBareStore(store);
        try {
            repositories.withCredential(request.getCredentialGrantId(), request.getExpectedHeadCommit(), repositoryFullName,
                    request.getRemoteBranch(), "FETCH", environment -> {
                        GitRepositoryManager.CommandResult result = repositories.run(List.of("git", "--git-dir",
                                        store.toString(), "fetch", "--no-tags", repositoryUrl,
                                        "+refs/heads/" + request.getRemoteBranch() + ":refs/heads/"
                                                + request.getRemoteBranch()), environment);
                        if (result.exitCode() != 0) {
                            throw classifyFetchFailure(result);
                        }
                        return null;
                    });

            // WorkspaceManager 以 baseRef/sourceBranch 在 bare Store 中创建
            // worktree，因此同步为本地受控分支引用。
            String actualHead = resolveCommit(store, "refs/heads/" + request.getRemoteBranch());
            if (actualHead == null) {
                throw invalid("GIT_REMOTE_BRANCH_NOT_FOUND", "远程分支不存在或未返回可用提交");
            }
            if (!actualHead.equalsIgnoreCase(request.getExpectedHeadCommit())) {
                throw new WorkerException(HttpStatus.CONFLICT, "GIT_REMOTE_SHA_MISMATCH", "远程分支 HEAD 与预期提交不一致");
            }
            configureOrigin(store, repositoryUrl);
            log.info("git store sync success repositoryId={} repository={} branch={} expectedHeadCommit={} actualHead={} storeCreated={}",
                    repositoryId, repositoryFullName, request.getRemoteBranch(), request.getExpectedHeadCommit(), actualHead,
                    created);
            return new GitStoreSyncResponse(repositoryId, request.getRemoteBranch(), actualHead, created);
        } catch (RuntimeException failure) {
            log.warn("git store sync failed repositoryId={} repository={} branch={} expectedHeadCommit={} failureCode={} exceptionType={}",
                    repositoryId, repositoryFullName, request.getRemoteBranch(), request.getExpectedHeadCommit(),
                    failureCode(failure), failure.getClass().getSimpleName());
            // 本次新建的 bare Store 在后续 fetch/校验失败时可能残留为空壳，删除避免下次以半成品复用；
            // 已存在的 Store 失败时保留原引用，不破坏既有内容。删除失败仅告警，不阻断异常抛回。
            if (created) {
                deleteStoreQuietly(store);
            }
            throw failure;
        }
    }

    /**
     * 尽力删除一个新建失败的空 Git Store 目录（递归）。
     */
    private void deleteStoreQuietly(Path store) {
        try (var stream = Files.walk(store)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // 尽力清理，单个文件删除失败不影响整体抛回。
                        }
                    });
        } catch (Exception ignored) {
            // 目录无法遍历时跳过清理，具体失败由上层显式异常表达。
        }
    }

    private boolean ensureBareStore(Path store) {
        if (!Files.exists(store)) {
            try {
                Files.createDirectories(store.getParent());
            } catch (Exception exception) {
                throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "GIT_STORE_CREATE_FAILED",
                        "无法创建 Git Store 目录");
            }
            repositories.requireSuccess(repositories.run(List.of("git", "init", "--bare", store.toString()), Map.of()),
                    "GIT_STORE_CREATE_FAILED", "无法初始化 bare Git Store");
            verifyBareStore(store);
            return true;
        }
        verifyBareStore(store);
        return false;
    }

    /**
     * 仅以 Git stderr 的固定特征做受限分类，不回传或记录原始 stderr。内部别名会在主后端折叠为
     * 既有 {@code GIT_STORE_FETCH_FAILED}，同时保留给受限失败诊断用于区分是否值得重试。
     */
    private WorkerException classifyFetchFailure(GitRepositoryManager.CommandResult result) {
        String stderr = result.stderr() == null ? "" : result.stderr().toLowerCase(Locale.ROOT);
        if (containsAny(stderr, "authentication failed", "http basic: access denied", "invalid username or password",
                "terminal prompts disabled", "403 forbidden", "401 unauthorized")) {
            return new WorkerException(HttpStatus.UNAUTHORIZED, "GIT_REMOTE_AUTH_FAILED", "远程 Git 认证失败");
        }
        if (containsAny(stderr, "couldn't find remote ref", "could not find remote ref")) {
            return new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, "GIT_REMOTE_BRANCH_NOT_FOUND", "远程分支不存在");
        }
        if (containsAny(stderr, "repository not found", "not found")) {
            return new WorkerException(HttpStatus.NOT_FOUND, "GIT_REMOTE_REPOSITORY_UNAVAILABLE", "远程仓库不可用");
        }
        if (containsAny(stderr, "rate limit", "429 too many requests", "too many requests")) {
            return new WorkerException(HttpStatus.SERVICE_UNAVAILABLE, "GIT_REMOTE_RATE_LIMITED", "远程 Git 服务限流");
        }
        if (containsAny(stderr, "could not resolve host", "failed to connect", "connection timed out", "connection reset",
                "remote end hung up", "tls", "ssl", "http 5", "502 bad gateway", "503 service unavailable")) {
            return new WorkerException(HttpStatus.BAD_GATEWAY, "GIT_REMOTE_NETWORK_FAILED", "远程 Git 网络连接失败");
        }
        return new WorkerException(HttpStatus.BAD_GATEWAY, "GIT_STORE_FETCH_FAILED", "无法同步远程 Git Store");
    }

    private boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private String failureCode(RuntimeException failure) {
        return failure instanceof WorkerException worker ? worker.getCode() : "UNCLASSIFIED";
    }

    private void verifyBareStore(Path store) {
        if (!Files.isDirectory(store)) {
            throw invalid("GIT_STORE_INVALID", "Git Store 路径不是目录");
        }
        GitRepositoryManager.CommandResult result = repositories.run(
                List.of("git", "--git-dir", store.toString(), "rev-parse", "--is-bare-repository"), Map.of());
        if (result.exitCode() != 0 || !"true".equals(result.stdout().trim())) {
            throw invalid("GIT_STORE_INVALID", "Git Store 不是合法 bare 仓库");
        }
    }

    private void configureOrigin(Path store, String repositoryUrl) {
        GitRepositoryManager.CommandResult result = repositories.run(
                List.of("git", "--git-dir", store.toString(), "remote", "get-url", "origin"), Map.of());
        if (result.exitCode() == 0) {
            repositories.requireSuccess(repositories.run(List.of("git", "--git-dir", store.toString(), "remote",
                            "set-url", "origin", repositoryUrl), Map.of()),
                    "GIT_ORIGIN_CONFIG_FAILED", "无法更新受控 Git origin");
            return;
        }
        repositories.requireSuccess(repositories.run(List.of("git", "--git-dir", store.toString(), "remote", "add",
                        "origin", repositoryUrl), Map.of()),
                "GIT_ORIGIN_CONFIG_FAILED", "无法配置受控 Git origin");
    }

    private String resolveCommit(Path store, String reference) {
        GitRepositoryManager.CommandResult result = repositories.run(List.of("git", "--git-dir", store.toString(),
                "rev-parse", "--verify", reference + "^{commit}"), Map.of());
        return result.exitCode() == 0 ? result.stdout().trim() : null;
    }

    private String validateRepositoryUrl(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                    || !GITHUB_HOST.equals(host.toLowerCase(Locale.ROOT))
                    || (uri.getPort() != -1 && uri.getPort() != 443)
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getRawPath() == null || uri.getRawPath().contains("%")
                    || !uri.getPath().matches(REPOSITORY_PATH_PATTERN)) {
                throw invalid("GIT_REMOTE_URL_INVALID", "仅允许受控 github.com HTTPS 仓库地址");
            }
            return uri.toString();
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("GIT_REMOTE_URL_INVALID", "仅允许受控 github.com HTTPS 仓库地址");
        }
    }

    private String repositoryFullName(String repositoryUrl) {
        URI uri = URI.create(repositoryUrl);
        String[] segments = uri.getPath().split("/");
        String repository = segments[2].endsWith(".git") ? segments[2].substring(0, segments[2].length() - 4)
                : segments[2];
        return segments[1] + "/" + repository;
    }

    private void validateBranch(String branch) {
        if (branch == null || !branch.matches(BRANCH_PATTERN) || branch.contains("..")
                || branch.endsWith("/") || branch.startsWith("-")) {
            throw invalid("GIT_REMOTE_BRANCH_INVALID", "远程分支格式不合法");
        }
    }

    private WorkerException invalid(String code, String message) {
        return new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }
}
