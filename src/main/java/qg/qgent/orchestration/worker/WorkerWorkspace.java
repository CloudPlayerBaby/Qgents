package qg.qgent.orchestration.worker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Worker 返回的持久开发现场状态（镜像 Worker 的 Workspace）。
 * 不包含任何宿主机路径；代码内容通过 Sandbox 工具访问。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerWorkspace {

    /** Workspace 编号。 */
    private UUID id;

    /** 所属项目编号。 */
    private UUID projectId;

    /** 不透明存储键，创建 Sandbox 时回传给 Worker 解析。 */
    private String storageKey;

    /** 生命周期状态，当前只有 READY。 */
    private String status;

    /** 各仓库副本状态。 */
    private List<WorkerWorkspaceRepository> repositories;

    /** 创建时间（ISO-8601 字符串）。 */
    private String createdAt;

    /** 最后更新时间（ISO-8601 字符串）。 */
    private String updatedAt;
}
