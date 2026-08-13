package qg.qgent.orchestration.worker;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import qg.qgent.orchestration.tool.GitDiffResult;
import qg.qgent.orchestration.tool.WorkspaceDiffAccess;

import java.util.Map;
import java.util.UUID;

/**
 * {@link WorkspaceDiffAccess} 的 Worker 实现：通过 Worker 的 workspace 级 {@code git/diff} 接口
 * 读取每个仓库的未提交改动（含未跟踪文件）并聚合，替代主后端本地执行 git。
 * <p>
 * 只读取不写：不调用 git/commit、git/push 或任何改写远端/工作树的方法。Worker 的 diff 以当前
 * HEAD 为基准（base=head=HEAD），真实反映沙箱写入后的工作树变更，不伪造 base/head commit。
 * <p>
 * {@code app.worker.enabled=true} 时作为默认 WorkspaceDiffAccess 启用。
 */
@Component
@Primary
@ConditionalOnProperty(name = "app.worker.enabled", havingValue = "true")
public class WorkerWorkspaceDiffAccess implements WorkspaceDiffAccess {

    private final SandboxWorkerClient client;
    private final SandboxSessionManager sessions;

    public WorkerWorkspaceDiffAccess(SandboxWorkerClient client, SandboxSessionManager sessions) {
        this.client = client;
        this.sessions = sessions;
    }

    @Override
    public GitDiffResult diff(UUID workspaceId) {
        try {
            SandboxSession session = sessions.require(workspaceId);
            StringBuilder combined = new StringBuilder();
            String baseCommit = null;
            String headCommit = null;
            for (Map.Entry<String, UUID> entry : session.getRepositoryByPath().entrySet()) {
                WorkerGitDiff diff = client.createWorkspaceGitDiff(workspaceId, entry.getValue());
                if (diff == null) {
                    return failure("workspace git diff unavailable");
                }
                if (baseCommit == null) {
                    baseCommit = diff.getHeadCommit();
                    headCommit = diff.getHeadCommit();
                }
                if (diff.getPatch() != null && !diff.getPatch().isBlank()) {
                    if (combined.length() > 0) {
                        combined.append('\n');
                    }
                    combined.append("===== ").append(entry.getKey()).append(" =====\n").append(diff.getPatch());
                }
            }
            return GitDiffResult.ok(combined.toString(), baseCommit, headCommit);
        } catch (RuntimeException e) {
            return failure("workspace git diff failed: " + e.getMessage());
        }
    }

    private GitDiffResult failure(String error) {
        return new GitDiffResult(false, "", "", "", error);
    }
}
