package qg.qgent.orchestration.tool;

import lombok.Data;

/**
 * Workspace 目录创建结果。目录创建是可持久化的工作树变更，但不会伪装成 Git 文件 Diff。
 */
@Data
public class WorkspaceDirectoryResult implements WorkspaceChangeResult {
    private boolean ok;
    private String path;
    private boolean created;
    private String error;
    private boolean infrastructureFailure;

    @Override
    public boolean isChanged() {
        return created;
    }

    public static WorkspaceDirectoryResult ok(String path, boolean created) {
        WorkspaceDirectoryResult result = new WorkspaceDirectoryResult();
        result.ok = true;
        result.path = path;
        result.created = created;
        return result;
    }

    public static WorkspaceDirectoryResult fail(String path, String error) {
        WorkspaceDirectoryResult result = new WorkspaceDirectoryResult();
        result.path = path;
        result.error = error;
        return result;
    }

    public static WorkspaceDirectoryResult infraFail(String path, String error) {
        WorkspaceDirectoryResult result = fail(path, error);
        result.infrastructureFailure = true;
        return result;
    }
}
