package qg.qgent.orchestration.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import qg.qgent.orchestration.tool.GitDiffResult;
import qg.qgent.orchestration.tool.Sha256;
import qg.qgent.orchestration.tool.WorkspaceDiffAccess;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
@Slf4j
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
            StringBuilder treeDigest = new StringBuilder();
            String baseCommit = null;
            String headCommit = null;
            int filesChanged = 0;
            int additions = 0;
            int deletions = 0;
            List<Map.Entry<String, UUID>> repos = new ArrayList<>(session.repositoryByPath().entrySet());
            repos.sort(Map.Entry.comparingByKey());
            for (Map.Entry<String, UUID> entry : repos) {
                WorkerGitDiff diff = client.createWorkspaceGitDiff(workspaceId, entry.getValue());
                if (diff == null) {
                    log.warn("WORKSPACE_GIT_DIFF_UNAVAILABLE workspaceId={} repositoryId={}",
                            workspaceId, entry.getValue());
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
                if (diff.getDiffHash() != null && !diff.getDiffHash().isBlank()) {
                    treeDigest.append(entry.getKey()).append('\n').append(diff.getDiffHash()).append('\n');
                }
                if (diff.getFiles() != null) {
                    filesChanged += diff.getFiles().size();
                    for (WorkerGitDiffFile file : diff.getFiles()) {
                        additions += file.getAdditions();
                        deletions += file.getDeletions();
                    }
                }
            }
            String workingTreeHash = treeDigest.length() == 0 ? null
                    : "sha256:" + Sha256.hex(treeDigest.toString().getBytes(StandardCharsets.UTF_8));
            log.info("workspace git diff ok workspaceId={} repos={} patchChars={} files={} add={} del={}",
                    workspaceId, session.repositoryByPath().size(), combined.length(),
                    filesChanged, additions, deletions);
            return GitDiffResult.ok(combined.toString(), baseCommit, headCommit, workingTreeHash,
                    filesChanged, additions, deletions);
        } catch (RuntimeException e) {
            log.error("WORKSPACE_GIT_DIFF_FAILED workspaceId={} category={}",
                    workspaceId, e.getClass().getSimpleName());
            return failure("workspace git diff failed: " + e.getMessage());
        }
    }

    private GitDiffResult failure(String error) {
        return GitDiffResult.failure(error);
    }
}
