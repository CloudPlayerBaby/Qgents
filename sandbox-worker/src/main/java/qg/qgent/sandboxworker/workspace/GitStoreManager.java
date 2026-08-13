package qg.qgent.sandboxworker.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import qg.qgent.sandboxworker.api.WorkerException;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
        return repositories.locked(repositoryId, () -> syncLocked(repositoryId, request, repositoryUrl, repositoryFullName));
    }

    private GitStoreSyncResponse syncLocked(UUID repositoryId, GitStoreSyncRequest request, String repositoryUrl,
            String repositoryFullName) {
        Path store = repositories.gitStore(repositoryId);
        boolean created = ensureBareStore(store);

        repositories.withCredential(request.getCredentialGrantId(), request.getExpectedHeadCommit(), repositoryFullName,
                request.getRemoteBranch(), "FETCH", environment -> {
            repositories.requireSuccess(repositories.run(List.of("git", "--git-dir", store.toString(), "fetch",
                    "--no-tags", repositoryUrl,
                    "+refs/heads/" + request.getRemoteBranch() + ":refs/heads/"
                            + request.getRemoteBranch()),
                    environment),
                    "GIT_STORE_FETCH_FAILED", "无法同步远程 Git Store");
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
        return new GitStoreSyncResponse(repositoryId, request.getRemoteBranch(), actualHead, created);
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
        String repository = segments[2].endsWith(".git") ? segments[2].substring(0, segments[2].length() - 4) : segments[2];
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
