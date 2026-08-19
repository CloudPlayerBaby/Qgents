package qg.qgent.orchestration.agent;

import qg.qgent.orchestration.tool.WorkspaceChangeResult;

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

    private final Set<String> changedFiles = new LinkedHashSet<>();
    private final Set<String> changedDirectories = new LinkedHashSet<>();
    private String lastToolError;

    void recordToolFailure(String error) {
        if (error != null && !error.isBlank()) {
            lastToolError = error;
        }
    }

    String lastToolError() {
        return lastToolError;
    }

    String recoveryHint() {
        if (lastToolError == null) {
            return "";
        }
        if (lastToolError.contains("FILE_PATCH_FAILED") || lastToolError.contains("PATCH_")) {
            return "；建议：先 read_file 获取最新内容和 sha256，再按实际内容重新生成完整 patch";
        }
        return "；建议：根据工具 errorCode 和 nextAction 修正参数后再试";
    }

    CodingWriteObserver observing(CodingWriteObserver delegate) {
        return (projectId, taskId, taskRunId, workspaceId, result) -> {
            record(result);
            if (delegate != null) {
                delegate.onWrite(projectId, taskId, taskRunId, workspaceId, result);
            }
        };
    }

    void record(WorkspaceChangeResult result) {
        if (result == null || !result.isOk() || !result.isChanged()
                || result.getPath() == null || result.getPath().isBlank()) {
            return;
        }
        Set<String> paths = result instanceof qg.qgent.orchestration.tool.WorkspaceDirectoryResult
                ? changedDirectories : changedFiles;
        if (paths.size() < MAX_CHANGED_PATHS || paths.contains(result.getPath())) {
            paths.add(result.getPath());
        }
    }

    boolean hasChangedWrite() {
        return !changedFiles.isEmpty() || !changedDirectories.isEmpty();
    }

    List<String> changedPaths() {
        return List.copyOf(changedFiles);
    }

    List<String> changedDirectories() {
        return List.copyOf(changedDirectories);
    }

    void addTo(List<String> target) {
        if (target == null) {
            return;
        }
        List<String> missing = new ArrayList<>(changedFiles);
        missing.removeAll(target);
        target.addAll(missing);
    }
}
