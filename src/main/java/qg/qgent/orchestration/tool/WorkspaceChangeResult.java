package qg.qgent.orchestration.tool;

/**
 * Workspace 内一次受控变更的最小结果。文件和目录变更都通过该接口进入 Coding
 * 事实账本；目录没有文件哈希，因此由具体结果类型提供额外字段。
 */
public interface WorkspaceChangeResult {

    boolean isOk();

    String getPath();

    boolean isChanged();

    String getError();

    boolean isInfrastructureFailure();
}
