package qg.qgent.sandboxworker.runtime;

import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * 将 Workspace 存储标识和受控仓库映射解析为安全路径。
 */
@Component
public class WorkspacePathResolver {
    public static final String GIT_MARKER = ".qgents-sandbox-git-marker";
    private static final Pattern STORAGE_KEY = Pattern.compile("workspaces/[0-9a-fA-F-]{36}");
    private final Path localRoot;
    private final Path dockerHostRoot;

    public WorkspacePathResolver(SandboxWorkerProperties properties) {
        localRoot = Path.of(properties.getWorkspaceLocalRoot()).toAbsolutePath().normalize();
        dockerHostRoot = Path.of(properties.getWorkspaceDockerHostRoot()).toAbsolutePath().normalize();
    }

    public Path resolveLocal(String storageKey) {
        validateStorageKey(storageKey);
        Path candidate = localRoot.resolve(relative(storageKey)).normalize();
        requireInside(candidate, localRoot);
        try {
            if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) throw invalid("Workspace 目录不存在");
            Path realRoot = localRoot.toRealPath();
            Path realCandidate = candidate.toRealPath();
            requireInside(realCandidate, realRoot);
            return realCandidate;
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("无法解析 Workspace 目录");
        }
    }

    public Path resolveDockerHost(String storageKey) {
        validateStorageKey(storageKey);
        Path candidate = dockerHostRoot.resolve(relative(storageKey)).normalize();
        requireInside(candidate, dockerHostRoot);
        return candidate;
    }

    public Path resolveRepositoryLocal(SandboxAllocation allocation, UUID repositoryId) {
        String relativePath = repositoryPath(allocation, repositoryId);
        Path workspace = resolveLocal(allocation.getWorkspaceStorageKey());
        Path repository = workspace.resolve(relativePath).normalize();
        requireInside(repository, workspace);
        try {
            if (!Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)) throw invalid("Workspace 仓库目录不存在");
            Path realRepository = repository.toRealPath();
            requireInside(realRepository, workspace);
            return realRepository;
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("无法解析 Workspace 仓库目录");
        }
    }

    public Path resolveRepositoryDockerHost(SandboxAllocation allocation, UUID repositoryId) {
        Path workspace = resolveDockerHost(allocation.getWorkspaceStorageKey());
        Path repository = workspace.resolve(repositoryPath(allocation, repositoryId)).normalize();
        requireInside(repository, workspace);
        return repository;
    }

    /**
     * 返回用于覆盖容器内 .git 指针的 Worker 受控空文件。
     */
    public Path resolveGitMarkerLocal(SandboxAllocation allocation) {
        Path workspace = resolveLocal(allocation.getWorkspaceStorageKey());
        Path marker = workspace.resolve(GIT_MARKER).normalize();
        try {
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != 0) {
                throw invalid("Workspace Git 隔离 marker 不合法");
            }
            Path real = marker.toRealPath();
            requireInside(real, workspace);
            return real;
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("无法解析 Workspace Git 隔离 marker");
        }
    }

    public Path resolveGitMarkerDockerHost(SandboxAllocation allocation) {
        resolveGitMarkerLocal(allocation);
        return resolveDockerHost(allocation.getWorkspaceStorageKey()).resolve(GIT_MARKER).normalize();
    }

    public String resolveRepositoryContainer(SandboxAllocation allocation, UUID repositoryId) {
        return "/workspace/" + repositoryPath(allocation, repositoryId).replace('\\', '/');
    }

    private String repositoryPath(SandboxAllocation allocation, UUID repositoryId) {
        String relativePath = allocation.getRepositoryPaths().get(repositoryId);
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("..") || Path.of(relativePath).isAbsolute())
            throw invalid("仓库编号未映射到合法的 Workspace 相对目录");
        return relativePath;
    }

    private Path relative(String storageKey) {
        return Path.of(storageKey.substring("workspaces/".length()));
    }

    private void validateStorageKey(String storageKey) {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches()) throw invalid("Workspace 存储标识不合法");
    }

    private void requireInside(Path candidate, Path root) {
        if (!candidate.startsWith(root)) throw invalid("Workspace 路径越界");
    }

    private WorkerException invalid(String message) {
        return new WorkerException(UNPROCESSABLE_ENTITY, "WORKSPACE_PATH_INVALID", message);
    }
}
