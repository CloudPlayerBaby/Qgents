package qg.qgent.orchestration.tool;

import lombok.Data;

/**
 * 一次 Workspace 读取操作的结果：成功时 ok=true 并给出 UTF-8 文本内容及其原始字节的
 * SHA-256（供 {@code apply_patch} 的 expectedHash 使用）；失败时 ok=false 并给出可回灌
 * 给 LLM 的错误说明。
 * <p>
 * sha256 必须来自文件原始字节（而非经行尾归一化的文本），与 Worker 的 file.patch 校验
 * 口径一致；主后端不得伪造或从宿主机路径推导。
 */
@Data
public class WorkspaceFileReadResult {

    /**
     * 是否读取成功。
     */
    private boolean ok;
    /**
     * 相对路径；仅成功时有意义。
     */
    private String path;
    /**
     * UTF-8 文本内容；仅成功时有意义。
     */
    private String content;
    /**
     * 文件原始字节的 SHA-256（64 位十六进制，小写）；仅成功时有意义。
     */
    private String sha256;
    /**
     * 失败原因（不得包含宿主机绝对路径或 Secret）。
     */
    private String error;

    public static WorkspaceFileReadResult ok(String path, String content, String sha256) {
        WorkspaceFileReadResult result = new WorkspaceFileReadResult();
        result.setOk(true);
        result.setPath(path);
        result.setContent(content);
        result.setSha256(sha256);
        return result;
    }

    public static WorkspaceFileReadResult fail(String path, String error) {
        WorkspaceFileReadResult result = new WorkspaceFileReadResult();
        result.setOk(false);
        result.setPath(path);
        result.setError(error);
        return result;
    }
}
