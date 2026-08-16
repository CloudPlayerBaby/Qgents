package qg.qgent.orchestration.tool;

import lombok.Data;

/**
 * 一次 Workspace 写操作的执行结果：成功时 ok=true 并给出写入的相对路径、写入后的文件
 * SHA-256（newSha256）与内容是否实际变化（changed）；失败时 ok=false 并区分两类错误，
 * 供 Agent 决定语义：
 * <ul>
 *   <li>工具级错误（{@link #fail}）：参数缺失、路径越界、内容超限等，模型可通过 TOOL 结果回灌自行纠正，不应判基础设施失败；</li>
 *   <li>基础设施错误（{@link #infraFail}）：workspace 不可用、文件系统写入失败等，重试同一写操作无意义，应由 Agent 映射 FAILED_INFRASTRUCTURE。</li>
 * </ul>
 * error 不得包含宿主机绝对路径或 Secret；newSha256 为内容哈希，不泄露文件内容本身。
 */
@Data
public class WorkspaceWriteResult {

    /**
     * 是否写入成功。
     */
    private boolean ok;
    /**
     * 相对路径；仅成功时有意义。
     */
    private String path;
    /**
     * 失败原因（不得包含宿主机绝对路径或 Secret）。
     */
    private String error;
    /**
     * 是否为基础设施级失败（workspace 不可用 / 文件系统错误）；仅 ok=false 时有意义。
     */
    private boolean infrastructureFailure;
    /**
     * 写入后文件内容的 SHA-256（小写 hex）；仅 ok=true 时有意义，Worker 透传、本地自算。
     */
    private String newSha256;
    /**
     * 本次写入是否实际改变了文件内容；仅 ok=true 时有意义。
     */
    private boolean changed;

    /**
     * 成功且未携带新哈希/变化信息的工厂（兼容旧调用方）。
     */
    public static WorkspaceWriteResult ok(String path) {
        return ok(path, null, false);
    }

    /**
     * 成功工厂：写入后的新 SHA-256 与是否实际变化。
     */
    public static WorkspaceWriteResult ok(String path, String newSha256, boolean changed) {
        WorkspaceWriteResult result = new WorkspaceWriteResult();
        result.setOk(true);
        result.setPath(path);
        result.setNewSha256(newSha256);
        result.setChanged(changed);
        return result;
    }

    /**
     * 工具级失败：模型可依据错误信息自行纠正，不判基础设施失败。
     */
    public static WorkspaceWriteResult fail(String path, String error) {
        WorkspaceWriteResult result = new WorkspaceWriteResult();
        result.setOk(false);
        result.setPath(path);
        result.setError(error);
        return result;
    }

    /**
     * 基础设施级失败：workspace 不可用、文件系统错误等，应映射 FAILED_INFRASTRUCTURE。
     */
    public static WorkspaceWriteResult infraFail(String path, String error) {
        WorkspaceWriteResult result = fail(path, error);
        result.setInfrastructureFailure(true);
        return result;
    }
}
