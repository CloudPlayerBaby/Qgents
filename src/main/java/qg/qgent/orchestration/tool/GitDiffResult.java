package qg.qgent.orchestration.tool;

/**
 * 一次 git diff 的只读结果（内部值对象，非持久化 Entity、非接口 DTO）。
 * diff 文本必须已脱敏；baseCommit/headCommit 是真实 Git 提交的标识，不得伪造。
 * <p>
 * 阶段 D 起附带工作树摘要：workingTreeHash 是当前工作树变更的确定性摘要（同工作树同哈希，
 * 供 Workspace Diff Preview 幂等），filesChanged/additions/deletions 为结构化统计（Worker 从
 * 每个仓库的 Diff files 聚合，本地实现可缺省为 0）。
 */
public record GitDiffResult(boolean ok, String diff, String baseCommit, String headCommit,
                            String workingTreeHash, int filesChanged, int additions, int deletions,
                            String error) {

    public static GitDiffResult ok(String diff, String baseCommit, String headCommit) {
        return ok(diff, baseCommit, headCommit, null, 0, 0, 0);
    }

    public static GitDiffResult ok(String diff, String baseCommit, String headCommit,
                                   String workingTreeHash, int filesChanged, int additions, int deletions) {
        return new GitDiffResult(true, diff, baseCommit, headCommit, workingTreeHash,
                filesChanged, additions, deletions, null);
    }

    public static GitDiffResult failure(String error) {
        return new GitDiffResult(false, "", "", "", null, 0, 0, 0, error);
    }

    public static GitDiffResult unavailable() {
        return failure("git diff is not available until the git service is ready");
    }
}
