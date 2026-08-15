package qg.qgent.orchestration.worker;

import java.util.UUID;

/**
 * 把 Workspace 相对路径解析为 Worker 需要的"仓库编号 + 仓库内相对路径"。
 * <p>
 * 当前端口的路径是 workspace 相对路径（首段为 worktree 名，如 {@code repo-1/src/Foo.java}），
 * 而 Worker 文件工具以仓库为根。多仓库时按首段匹配 workspacePath，单仓库时直接使用唯一仓库。
 */
final class WorkerPathResolver {

    private WorkerPathResolver() {
    }

    static Target resolve(SandboxSession session, String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        int slash = path.indexOf('/');
        if (slash > 0) {
            String first = path.substring(0, slash);
            UUID repositoryId = session.repositoryByPath().get(first);
            if (repositoryId != null) {
                return new Target(repositoryId, path.substring(slash + 1));
            }
        }
        UUID single = session.singleRepository();
        return single == null ? null : new Target(single, path);
    }

    /**
     * 解析结果：目标仓库编号 + 仓库内相对路径。
     */
    record Target(UUID repositoryId, String relativePath) {
    }
}
