package qg.qgent.orchestration.worker;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 请求准备一个持久 Workspace 的完整声明（镜像 Worker 的 WorkspaceProvisionRequest）。
 * 幂等：同一 workspaceId 再次提交相同规格时 Worker 返回已就绪状态，规格冲突返回 409。
 */
@Data
public class WorkerWorkspaceProvisionRequest {

    /**
     * Workspace 所属项目编号。
     */
    private UUID projectId;

    /**
     * Workspace 下需要准备的独立仓库副本。
     */
    private List<WorkerWorkspaceRepositoryRequest> repositories;
}
