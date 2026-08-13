package qg.qgent.orchestration.worker;

import lombok.Data;

import java.util.UUID;

/**
 * Worker 返回的 Workspace 内单个仓库副本状态（在请求字段之上补充真实 commit 信息）。
 */
@Data
public class WorkerWorkspaceRepository {

    /** 项目仓库绑定编号。 */
    private UUID repositoryId;

    /** 创建独立仓库时使用的基线引用。 */
    private String baseRef;

    /** 功能分支。 */
    private String sourceBranch;

    /** Workspace 内的一级相对目录名称。 */
    private String workspacePath;

    /** 工作区创建时的真实基线提交。 */
    private String baseCommit;

    /** 当前 HEAD 提交，未接受过 Diff 时为 null。 */
    private String headCommit;
}
