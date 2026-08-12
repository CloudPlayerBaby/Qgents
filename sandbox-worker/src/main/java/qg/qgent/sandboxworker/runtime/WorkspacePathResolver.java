package qg.qgent.sandboxworker.runtime;

import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.UUID;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/** 将不透明 Workspace 存储标识解析为受控的本地路径和 Docker 宿主机路径。 */
@Component
public class WorkspacePathResolver {
    private static final Pattern STORAGE_KEY = Pattern.compile("workspaces/[A-Za-z0-9/_-]+");

    private final Path localRoot;
    private final Path dockerHostRoot;

    public WorkspacePathResolver(SandboxWorkerProperties properties) {
        localRoot = Path.of(properties.getWorkspaceLocalRoot()).toAbsolutePath().normalize();
        dockerHostRoot = Path.of(properties.getWorkspaceDockerHostRoot()).toAbsolutePath().normalize();
    }

    /**
     * 解析并验证 Worker 本地可见的 Workspace 路径。
     * 路径必须已存在、位于配置根目录下，并且真实路径不能通过符号链接逃逸。
     */
    public Path resolveLocal(String storageKey) {
        validateStorageKey(storageKey);
        Path candidate = localRoot.resolve(relative(storageKey)).normalize();
        requireInside(candidate, localRoot);
        try {
            if (!Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("Workspace 目录不存在");
            }
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

    /**
     * 根据 Sandbox 创建时登记的仓库映射解析本地独立仓库，并阻止目录穿越和符号链接逃逸。
     */
    public Path resolveRepositoryLocal(SandboxAllocation allocation, UUID repositoryId) {
        String relativePath = allocation.getRepositoryPaths().get(repositoryId);
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("..")
                || Path.of(relativePath).isAbsolute()) {
            throw invalid("仓库编号未映射到合法的 Workspace 相对目录");
        }
        Path workspace = resolveLocal(allocation.getWorkspaceStorageKey());
        Path repository = workspace.resolve(relativePath).normalize();
        requireInside(repository, workspace);
        try {
            if (!Files.isDirectory(repository, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("Workspace 仓库目录不存在");
            }
            Path realRepository = repository.toRealPath();
            requireInside(realRepository, workspace);
            return realRepository;
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("无法解析 Workspace 仓库目录");
        }
    }

    public String resolveRepositoryContainer(SandboxAllocation allocation, UUID repositoryId) {
        String relativePath = allocation.getRepositoryPaths().get(repositoryId);
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("..")
                || Path.of(relativePath).isAbsolute()) {
            throw invalid("仓库编号未映射到合法的 Workspace 相对目录");
        }
        return "/workspace/" + relativePath.replace('\\', '/');
    }

    private Path relative(String storageKey) {
        return Path.of(storageKey.substring("workspaces/".length()));
    }

    private void validateStorageKey(String storageKey) {
        if (storageKey == null || !STORAGE_KEY.matcher(storageKey).matches() || storageKey.contains("..")) {
            throw invalid("Workspace 存储标识不合法");
        }
    }

    private void requireInside(Path candidate, Path root) {
        if (!candidate.startsWith(root)) {
            throw invalid("Workspace 路径越界");
        }
    }

    private WorkerException invalid(String message) {
        return new WorkerException(UNPROCESSABLE_ENTITY, "WORKSPACE_PATH_INVALID", message);
    }
}
