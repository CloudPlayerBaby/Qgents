package qg.qgent.sandboxworker.tool;

import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/**
 * 将 Agent 提交的仓库相对路径解析为受控本地文件。
 */
@Component
public class RepositoryFileResolver {
    public Path resolveExisting(Path repository, String relativePath) {
        Path candidate = resolve(repository, relativePath);
        try {
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw invalid("文件不存在");
            }
            Path real = candidate.toRealPath();
            requireInside(real, repository);
            return real;
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("无法解析文件路径");
        }
    }

    public Path resolveForWrite(Path repository, String relativePath) {
        Path candidate = resolve(repository, relativePath);
        Path parent = candidate.getParent();
        try {
            if (parent == null) {
                throw invalid("写入文件的父目录不存在或越界");
            }
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(candidate)) {
                throw invalid("写入目标不能是符号链接");
            }
            validateParentChain(repository, parent);
            Files.createDirectories(parent);
            validateParentChain(repository, parent);
            return parent.resolve(candidate.getFileName());
        } catch (Exception exception) {
            if (exception instanceof WorkerException workerException) {
                throw workerException;
            }
            throw invalid("写入文件的父目录不存在或越界");
        }
    }

    /**
     * 解析仓库内目录创建目标。目标可以不存在，但已存在的父路径必须真实位于仓库内，
     * 且不能通过符号链接绕出仓库边界。
     */
    public Path resolveForDirectoryCreate(Path repository, String relativePath) {
        Path candidate = resolve(repository, relativePath);
        try {
            validateExistingChain(repository, candidate);
            return candidate;
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("无法解析目录路径");
        }
    }

    /**
     * 目录创建后再次确认路径链未被替换为符号链接且真实路径仍在仓库内。
     */
    public void verifyCreatedPath(Path repository, Path target) {
        validateExistingChain(repository, target);
        try {
            Path realRepository = repository.toRealPath();
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realRepository)) {
                throw invalid("文件路径越过仓库边界");
            }
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("路径创建后无法确认仓库边界");
        }
    }

    private Path resolve(Path repository, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw invalid("文件路径必须是仓库内相对路径");
        }
        try {
            Path relative = Path.of(relativePath);
            if (relative.isAbsolute() || containsParentTraversal(relative)) {
                throw invalid("文件路径必须是仓库内相对路径");
            }
            Path candidate = repository.resolve(relative).normalize();
            requireInside(candidate, repository);
            return candidate;
        } catch (WorkerException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("文件路径必须是仓库内相对路径");
        }
    }

    private boolean containsParentTraversal(Path path) {
        for (Path segment : path) {
            if ("..".equals(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private void requireInside(Path path, Path repository) {
        if (!path.startsWith(repository)) {
            throw invalid("文件路径越过仓库边界");
        }
    }

    private void validateParentChain(Path repository, Path parent) {
        validateExistingChain(repository, parent);
    }

    /**
     * 在创建缺失目录前逐级检查已存在路径。拒绝任何符号链接，避免 Windows 挂载或 POSIX
     * 工作树上的 realpath 权限差异绕过仓库边界校验。
     */
    private void validateExistingChain(Path repository, Path path) {
        Path current = path;
        while (current != null && current.startsWith(repository)) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)) {
                    throw invalid("目录路径不能包含符号链接");
                }
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw invalid("目录路径的父级必须是目录");
                }
            }
            if (current.equals(repository)) {
                return;
            }
            current = current.getParent();
        }
        throw invalid("文件路径越过仓库边界");
    }

    private WorkerException invalid(String message) {
        return new WorkerException(UNPROCESSABLE_ENTITY, "TOOL_PATH_INVALID", message);
    }
}
