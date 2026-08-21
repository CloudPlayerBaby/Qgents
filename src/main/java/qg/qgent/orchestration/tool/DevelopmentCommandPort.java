package qg.qgent.orchestration.tool;

import java.util.UUID;

/**
 * Coding Agent 到 Worker 固定开发命令的受控端口。
 */
public interface DevelopmentCommandPort {

    DevelopmentCommandResult run(UUID workspaceId, String repositoryPath, DevelopmentCommandId commandId);

    static DevelopmentCommandPort unavailable() {
        return (workspaceId, repositoryPath, commandId) -> DevelopmentCommandResult.unavailable(commandId);
    }
}
