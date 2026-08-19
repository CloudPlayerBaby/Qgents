package qg.qgent.orchestration.tool;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 在 Workspace 内执行命令的端口。
 * <p>
 * 本阶段仅定义端口，不实现完整 Sandbox，也不允许在宿主机任意执行命令：
 * 当前提供 {@link DisabledExecutionPort}，任何调用都返回明确的"未就绪"结果。
 * 真实 Sandbox 接入后由受控执行实现替换，且必须保证最小权限、超时与 Project 隔离。
 */
public interface ExecutionPort {

    /**
     * 在指定 Workspace 内执行一条命令并返回结果。
     *
     * @param workspaceId 目标 Workspace。
     * @param command     命令与参数，不得包含明文 Secret。
     * @param timeout     执行超时。
     * @return 执行结果（输出已脱敏）。
     */
    ExecutionResult execute(UUID workspaceId, List<String> command, Duration timeout);

    /**
     * 在 Workspace 的指定仓库目录内执行命令。默认实现保持单仓库调用兼容；
     * 需要多仓库隔离的执行器应覆盖此方法并校验 repositoryPath。
     *
     * @param workspaceId 目标 Workspace。
     * @param repositoryPath Workspace 内的仓库相对目录；空值表示 Workspace 根目录。
     * @param command 命令与参数。
     * @param timeout 执行超时。
     * @return 执行结果。
     */
    default ExecutionResult execute(UUID workspaceId, String repositoryPath, List<String> command,
                                    Duration timeout) {
        return execute(workspaceId, command, timeout);
    }
}
