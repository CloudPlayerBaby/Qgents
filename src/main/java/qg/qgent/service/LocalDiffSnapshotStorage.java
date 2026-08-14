package qg.qgent.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Configured local fallback for controlled Diff snapshot storage. */
@Service
public class LocalDiffSnapshotStorage implements DiffSnapshotStorage {
    private final Path root;

    public LocalDiffSnapshotStorage(@Value("${app.diff-snapshot-root:./data/diff-snapshots}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public String store(UUID diffId, String patch) {
        try {
            Files.createDirectories(root);
            String key = "diff-snapshots/" + diffId + ".patch";
            Path target = resolve(key);
            if (!target.startsWith(root)) {
                throw new IllegalStateException("Diff snapshot path escapes configured root");
            }
            Files.writeString(target, patch == null ? "" : patch, StandardCharsets.UTF_8);
            return key;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to persist Diff snapshot", exception);
        }
    }

    @Override
    public String load(String snapshotKey) {
        try {
            return Files.readString(resolve(snapshotKey), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read Diff snapshot", exception);
        }
    }

    private Path resolve(String key) {
        if (key == null || !key.matches("diff-snapshots/[0-9a-fA-F-]{36}\\.patch")) {
            throw new IllegalArgumentException("Invalid Diff snapshot key");
        }
        Path target = root.resolve(key.substring("diff-snapshots/".length())).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Diff snapshot path escapes configured root");
        }
        return target;
    }
}
