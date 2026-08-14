package qg.qgent.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LocalDiffSnapshotStorageTest {
    @TempDir
    Path root;

    @Test
    void storesAndReadsPatchThroughAnOpaqueKey() {
        LocalDiffSnapshotStorage storage = new LocalDiffSnapshotStorage(root.toString());
        String key = storage.store(UUID.randomUUID(), "diff --git a/A.java b/A.java\n");

        assertThat(key).startsWith("diff-snapshots/").doesNotContain(root.toString());
        assertThat(storage.load(key)).isEqualTo("diff --git a/A.java b/A.java\n");
    }
}
