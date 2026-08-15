package qg.qgent.service;

import java.util.UUID;

/**
 * Controlled storage for immutable raw Git patches referenced by Diff.snapshotKey.
 */
public interface DiffSnapshotStorage {
    String store(UUID diffId, String patch);

    /**
     * Reads a snapshot through its opaque storage key; callers must authorize the owning Diff first.
     */
    String load(String snapshotKey);
}
