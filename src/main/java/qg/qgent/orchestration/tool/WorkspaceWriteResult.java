package qg.qgent.orchestration.tool;

import lombok.Data;

/**
 * 一次 Workspace 写操作的执行结果：成功时 ok=true 并给出写入的相对路径，
 * 失败时 ok=false 并给出可回灌给 LLM 的明确错误说明。
 */
@Data
public class WorkspaceWriteResult {

    /** 是否写入成功。 */
    private boolean ok;
    /** 相对路径；仅成功时有意义。 */
    private String path;
    /** 失败原因（不得包含宿主机绝对路径或 Secret）。 */
    private String error;

    public static WorkspaceWriteResult ok(String path) {
        WorkspaceWriteResult result = new WorkspaceWriteResult();
        result.setOk(true);
        result.setPath(path);
        return result;
    }

    public static WorkspaceWriteResult fail(String path, String error) {
        WorkspaceWriteResult result = new WorkspaceWriteResult();
        result.setOk(false);
        result.setPath(path);
        result.setError(error);
        return result;
    }
}
