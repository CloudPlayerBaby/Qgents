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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 使用 JVM keyed lock 与共享文件锁串行化 Workspace 操作。
 */
@Component
@RequiredArgsConstructor
public class WorkspaceOperationLock {
    private final SandboxWorkerProperties properties;
    private final ConcurrentMap<UUID, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    /**
     * 同 JVM 先串行，再获取跨进程文件锁，避免 OverlappingFileLockException。
     */
    public <T> T execute(String storageKey, LockedOperation<T> operation) {
        UUID workspaceId = parseWorkspaceId(storageKey);
        ReentrantLock localLock = localLocks.computeIfAbsent(workspaceId, ignored -> new ReentrantLock());
        localLock.lock();
        try {
            Path directory = Path.of(properties.getWorkspaceMetadataRoot()).toAbsolutePath().normalize().resolve("locks");
            Files.createDirectories(directory);
            try (FileChannel channel = FileChannel.open(directory.resolve(workspaceId + ".lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
                return operation.run();
            }
        } catch (WorkerException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.INTERNAL_SERVER_ERROR, "WORKSPACE_LOCK_FAILED", "无法获取 Workspace 操作锁");
        } finally {
            localLock.unlock();
        }
    }

    private UUID parseWorkspaceId(String storageKey) {
        try {
            if (storageKey == null || !storageKey.startsWith("workspaces/")) throw new IllegalArgumentException();
            return UUID.fromString(storageKey.substring("workspaces/".length()));
        } catch (Exception exception) {
            throw new WorkerException(HttpStatus.UNPROCESSABLE_ENTITY, "WORKSPACE_STORAGE_KEY_INVALID", "Workspace 存储标识不合法");
        }
    }

    @FunctionalInterface
    public interface LockedOperation<T> {
        T run();
    }
}
