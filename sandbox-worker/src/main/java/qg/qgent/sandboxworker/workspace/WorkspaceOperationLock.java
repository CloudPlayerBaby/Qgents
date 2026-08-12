package qg.qgent.sandboxworker.workspace;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import qg.qgent.sandboxworker.api.WorkerException;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * 使用共享元数据目录中的文件锁串行化 Workspace 删除与 Sandbox 创建。
 * 多个 Worker 必须挂载同一 metadata root，文件锁才具备跨进程互斥能力。
 */
@Component
@RequiredArgsConstructor
public class WorkspaceOperationLock {
    private final SandboxWorkerProperties properties;

    /** 在指定 Workspace 的跨进程互斥锁内执行操作。 */
    public <T> T execute(String storageKey, LockedOperation<T> operation) {
        UUID workspaceId = parseWorkspaceId(storageKey);
        Path lockDirectory = Path.of(properties.getWorkspaceMetadataRoot()).toAbsolutePath().normalize()
                .resolve("locks");
        try {
            Files.createDirectories(lockDirectory);
            Path lockFile = lockDirectory.resolve(workspaceId + ".lock");
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                return operation.run();
            }
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "WORKSPACE_LOCK_FAILED", "无法获取 Workspace 操作锁");
        }
    }

    private UUID parseWorkspaceId(String storageKey) {
        try {
            if (storageKey == null || !storageKey.startsWith("workspaces/")) {
                throw new IllegalArgumentException();
            }
            return UUID.fromString(storageKey.substring("workspaces/".length()));
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "WORKSPACE_STORAGE_KEY_INVALID", "Workspace 存储标识不合法");
        }
    }

    @FunctionalInterface
    public interface LockedOperation<T> {
        T run();
    }
}
