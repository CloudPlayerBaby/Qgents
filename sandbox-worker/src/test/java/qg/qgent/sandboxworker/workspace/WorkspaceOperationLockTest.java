package qg.qgent.sandboxworker.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import qg.qgent.sandboxworker.config.SandboxWorkerProperties;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceOperationLockTest {
    @TempDir Path root;

    @Test
    void serializesSameWorkspaceInsideJvmBeforeTakingFileLock() throws Exception {
        SandboxWorkerProperties properties = new SandboxWorkerProperties();
        properties.setWorkspaceMetadataRoot(root.toString());
        WorkspaceOperationLock lock = new WorkspaceOperationLock(properties);
        String storageKey = "workspaces/" + UUID.randomUUID();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> execute(lock, storageKey, start, active, maximum));
            var second = executor.submit(() -> execute(lock, storageKey, start, active, maximum));
            start.countDown();
            first.get();
            second.get();
        }
        assertEquals(1, maximum.get());
    }

    private void execute(WorkspaceOperationLock lock, String key, CountDownLatch start,
                         AtomicInteger active, AtomicInteger maximum) {
        try { start.await(); } catch (InterruptedException exception) { throw new RuntimeException(exception); }
        lock.execute(key, () -> {
            int current = active.incrementAndGet();
            maximum.accumulateAndGet(current, Math::max);
            try { Thread.sleep(50); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            active.decrementAndGet();
            return null;
        });
    }
}
