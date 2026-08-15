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
            Path realParent = parent.toRealPath();
            requireInside(realParent, repository);
            return realParent.resolve(candidate.getFileName());
        } catch (Exception exception) {
            throw invalid("写入文件的父目录不存在或越界");
        }
    }

    private Path resolve(Path repository, String relativePath) {
        if (relativePath == null || relativePath.isBlank() || relativePath.contains("..")
                || Path.of(relativePath).isAbsolute()) {
            throw invalid("文件路径必须是仓库内相对路径");
        }
        Path candidate = repository.resolve(relativePath).normalize();
        requireInside(candidate, repository);
        return candidate;
    }

    private void requireInside(Path path, Path repository) {
        if (!path.startsWith(repository)) {
            throw invalid("文件路径越过仓库边界");
        }
    }

    private WorkerException invalid(String message) {
        return new WorkerException(UNPROCESSABLE_ENTITY, "TOOL_PATH_INVALID", message);
    }
}
