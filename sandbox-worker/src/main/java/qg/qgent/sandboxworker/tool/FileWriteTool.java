package qg.qgent.sandboxworker.tool;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import static org.springframework.http.HttpStatus.CONFLICT;

/**
 * 使用旧内容哈希校验和原子替换写入 UTF-8 文本文件。
 */
@Component
@RequiredArgsConstructor
public class FileWriteTool implements SandboxTool {
    private static final int SANDBOX_UID = 10001;
    private static final int SANDBOX_GID = 10001;
    private static final Set<PosixFilePermission> DEFAULT_FILE_PERMISSIONS = Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ);

    private final RepositoryFileResolver files;

    @Override
    public String name() {
        return "file.write";
    }

    @Override
    public boolean requiresRepository() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String relativePath = ToolArguments.string(arguments, "path", 1024);
        String expectedHash = ToolArguments.string(arguments, "expectedHash", 64);
        String content = ToolArguments.optionalString(arguments, "content", "", 2 * 1024 * 1024);
        Path target = files.resolveForWrite(context.getLocalRepository(), relativePath);
        try {
            byte[] previous = Files.exists(target) ? Files.readAllBytes(target) : new byte[0];
            String actualHash = FileReadTool.sha256(previous);
            if (!actualHash.equalsIgnoreCase(expectedHash)) {
                throw new WorkerException(CONFLICT, "FILE_HASH_MISMATCH", "文件已经发生变化，请重新读取后再写入");
            }
            byte[] next = content.getBytes(StandardCharsets.UTF_8);
            atomicReplace(target, next);
            return ToolResult.value(Map.of("path", relativePath, "sha256", FileReadTool.sha256(next),
                    "bytes", next.length));
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("写入文件失败", exception);
        }
    }
    /**
     * 通过临时文件 + 原子替换写入目标，并保留/修复目标文件的 POSIX 权限与沙箱用户属主；
     * 任一步骤失败都保证目标文件要么保持原样，要么已被完整替换（不会出现部分写入）。
     * 与 {@link FilePatchTool} 共享同一写入路径。
     */
    static void atomicReplace(Path target, byte[] next) throws IOException {
        Set<PosixFilePermission> permissions = existingPermissions(target);
        Path temporary = Files.createTempFile(target.getParent(), ".qgents-", ".tmp");
        try {
            Files.write(temporary, next);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            grantSandboxOwnership(target, permissions);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Set<PosixFilePermission> existingPermissions(Path target) {
        try {
            return Files.getPosixFilePermissions(target, LinkOption.NOFOLLOW_LINKS);
        } catch (UnsupportedOperationException | IOException exception) {
            return DEFAULT_FILE_PERMISSIONS;
        }
    }

    private static void grantSandboxOwnership(Path target, Set<PosixFilePermission> permissions) throws IOException {
        // 先设置权限（保证 group/others 可读），即使后续 chown 失败，沙箱进程仍能读取文件。
        try {
            Files.setPosixFilePermissions(target, permissions);
        } catch (UnsupportedOperationException e) {
            // 非 POSIX 文件系统（如 Windows 挂载）不支持权限设置，跳过。
        }
        PosixFileAttributeView view = Files.getFileAttributeView(target, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            return;
        }
        try {
            Files.setAttribute(target, "unix:uid", SANDBOX_UID, LinkOption.NOFOLLOW_LINKS);
            Files.setAttribute(target, "unix:gid", SANDBOX_GID, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | UnsupportedOperationException e) {
            // chown 需要特权，失败不阻断写入；文件权限已在上方保证可读。
        }
    }
}
