package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

/**
 * 在受控 Repository 内递归、幂等创建目录。
 * 不创建 .gitkeep；目录本身不会产生 Git 文件 Diff。
 */
@Component
@RequiredArgsConstructor
public class DirectoryCreateTool implements SandboxTool {
    private static final int SANDBOX_UID = 10001;
    private static final int SANDBOX_GID = 10001;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE);

    private final RepositoryFileResolver files;

    @Override
    public String name() {
        return "directory.create";
    }

    @Override
    public boolean requiresRepository() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String relativePath = ToolArguments.string(arguments, "path", 1024);
        Path target = files.resolveForDirectoryCreate(context.getLocalRepository(), relativePath);
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new WorkerException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                            "TOOL_PATH_INVALID", "目录目标必须是目录且不能是符号链接");
                }
                return ToolResult.value(Map.of("path", relativePath, "created", false));
            }
            Files.createDirectories(target);
            files.verifyCreatedPath(context.getLocalRepository(), target);
            grantSandboxOwnership(target);
            return ToolResult.value(Map.of("path", relativePath, "created", true));
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WorkerException(INTERNAL_SERVER_ERROR, "DIRECTORY_CREATE_FAILED", "创建目录失败");
        }
    }

    private void grantSandboxOwnership(Path target) {
        try (var paths = Files.walk(target)) {
            for (Path path : paths.toList()) {
                try {
                    Files.setPosixFilePermissions(path, DIRECTORY_PERMISSIONS);
                    Files.setAttribute(path, "unix:uid", SANDBOX_UID, LinkOption.NOFOLLOW_LINKS);
                    Files.setAttribute(path, "unix:gid", SANDBOX_GID, LinkOption.NOFOLLOW_LINKS);
                } catch (UnsupportedOperationException | java.io.IOException ignored) {
                    // Windows 挂载等非 POSIX 文件系统不支持这些属性，沿用现有降级策略。
                }
            }
        } catch (java.io.IOException ignored) {
            // 目录已经创建；权限修复失败由后续实际写入给出明确错误。
        }
    }
}
