package qg.qgent.orchestration.tool;

/**
 * 一次 git diff 的只读结果（内部值对象，非持久化 Entity、非接口 DTO）。
 * diff 文本必须已脱敏；baseCommit/headCommit 是真实 Git 提交的标识，不得伪造。
 */
public record GitDiffResult(boolean ok, String diff, String baseCommit, String headCommit, String error) {

    public static GitDiffResult ok(String diff, String baseCommit, String headCommit) {
        return new GitDiffResult(true, diff, baseCommit, headCommit, null);
    }

    public static GitDiffResult unavailable() {
        return new GitDiffResult(false, "", "", "", "git diff is not available until the git service is ready");
    }
}
