package qg.qgent.orchestration.agent;

import qg.qgent.orchestration.tool.WorkspaceWriteResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 当前 TaskRun 内由写工具返回的可信变更事实账本。
 * <p>
 * 账本只接收成功且 {@code changed=true} 的相对路径，并限制条目数，避免工具历史压缩后
 * 丢失早期写入事实，也避免把无界事实重新注入模型上下文。
 */
final class ChangedWriteFactLedger {

    static final int MAX_CHANGED_PATHS = 64;

    private final Set<String> changedPaths = new LinkedHashSet<>();

    CodingWriteObserver observing(CodingWriteObserver delegate) {
        return (projectId, taskId, taskRunId, workspaceId, result) -> {
            record(result);
            if (delegate != null) {
                delegate.onWrite(projectId, taskId, taskRunId, workspaceId, result);
            }
        };
    }

    void record(WorkspaceWriteResult result) {
        if (result == null || !result.isOk() || !result.isChanged()
                || result.getPath() == null || result.getPath().isBlank()) {
            return;
        }
        if (changedPaths.size() < MAX_CHANGED_PATHS || changedPaths.contains(result.getPath())) {
            changedPaths.add(result.getPath());
        }
    }

    boolean hasChangedWrite() {
        return !changedPaths.isEmpty();
    }

    List<String> changedPaths() {
        return List.copyOf(changedPaths);
    }

    void addTo(List<String> target) {
        if (target == null) {
            return;
        }
        List<String> missing = new ArrayList<>(changedPaths);
        missing.removeAll(target);
        target.addAll(missing);
    }
}
