package qg.qgent.orchestration.worker;

import lombok.Data;

import java.util.UUID;

/**
 * 准备 Workspace 时单个独立仓库副本的声明（镜像 Worker 的 WorkspaceRepositoryRequest）。
 * 只携带资源编号与受控 Git 引用，不接受宿主机路径、远端地址或凭证。
 */
@Data
public class WorkerWorkspaceRepositoryRequest {

    /**
     * 项目仓库绑定编号，同时用于解析共享 Git Store。
     */
    private UUID repositoryId;

    /**
     * 创建独立仓库时使用的基线提交或受控引用。
     */
    private String baseRef;

    /**
     * Workspace 内要创建或复用的功能分支。
     */
    private String sourceBranch;

    /**
     * Workspace 内的一级相对目录名称。
     */
    private String workspacePath;
}
