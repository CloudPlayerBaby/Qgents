package qg.qgent.orchestration.tool;

import java.util.UUID;

/**
 * 对 Workspace 当前 Git Diff 的只读访问端口（git_diff 工具）。
 * <p>
 * 刻意只暴露读取：没有 commit、push、MR 或任何改写远端/工作树的方法，
 * 从结构上保证 Review Agent 只能读取 diff，无法执行 Git 写操作。当前提供
 * {@link DisabledWorkspaceDiffAccess} 占位实现，真实 Git 服务/Sandbox 接入后
 * 由受控实现替换，且必须基于真实 Git 提供方数据，不得伪造 base/head commit。
 */
public interface WorkspaceDiffAccess {

    /**
     * 读取指定 Workspace 的当前修改 diff（相对 base 的工作树变更）。
     *
     * @param workspaceId 目标 Workspace。
     * @return 只读 diff 结果；diff 不可用（未就绪/失败）时 ok=false 并给出原因。
     */
    GitDiffResult diff(UUID workspaceId);
}
