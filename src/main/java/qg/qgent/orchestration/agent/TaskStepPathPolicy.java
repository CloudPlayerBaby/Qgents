package qg.qgent.orchestration.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime write boundary for one materialized TaskStep.
 * <p>
 * An empty policy means a legacy step whose scope was not persisted yet and is
 * intentionally kept backward compatible. New planner steps persist at least
 * their repository root (or their exact files), so they do not rely on this
 * fallback. Paths are workspace-relative and use forward slashes.
 */
public final class TaskStepPathPolicy {

    private final List<String> allowedPaths;

    private TaskStepPathPolicy(Collection<String> paths) {
        Set<String> normalized = new LinkedHashSet<>();
        if (paths != null) {
            for (String path : paths) {
                String value = normalize(path);
                if (value != null) {
                    normalized.add(value);
                }
            }
        }
        this.allowedPaths = List.copyOf(normalized);
    }

    /** Empty list is the legacy unrestricted policy. */
    public static TaskStepPathPolicy of(Collection<String> paths) {
        return new TaskStepPathPolicy(paths);
    }

    public boolean isLegacyUnrestricted() {
        return allowedPaths.isEmpty();
    }

    /**
     * Exact paths and descendants of declared directory/repository paths are
     * accepted. The writer still validates the real filesystem target, so a
     * file accidentally used as a directory cannot be bypassed.
     */
    public boolean allows(String path) {
        String candidate = normalize(path);
        if (candidate == null) {
            return false;
        }
        if (isLegacyUnrestricted()) {
            return true;
        }
        for (String allowed : allowedPaths) {
            if (allowed.isEmpty() || candidate.equals(allowed) || candidate.startsWith(allowed + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Allows a directory that is an ancestor of a declared file path. */
    public boolean allowsDirectory(String path) {
        String candidate = normalize(path);
        if (candidate == null) {
            return false;
        }
        if (isLegacyUnrestricted()) {
            return true;
        }
        for (String allowed : allowedPaths) {
            if (allowed.isEmpty() || candidate.equals(allowed) || candidate.startsWith(allowed + "/")
                    || allowed.startsWith(candidate + "/")) {
                return true;
            }
        }
        return false;
    }

    public List<String> allowedPaths() {
        return allowedPaths;
    }

    /**
     * Normalizes a workspace-relative path and rejects absolute paths and any
     * parent traversal segment. A null return means the input is unsafe.
     */
    public static String normalize(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String value = path.trim().replace('\\', '/');
        if (value.startsWith("/") || value.matches("^[A-Za-z]:/.*")) {
            return null;
        }
        String[] segments = value.split("/", -1);
        List<String> safe = new ArrayList<>();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment) || segment.indexOf('\0') >= 0) {
                return null;
            }
            safe.add(segment);
        }
        return String.join("/", safe);
    }
}
