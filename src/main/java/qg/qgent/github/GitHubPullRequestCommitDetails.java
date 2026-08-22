package qg.qgent.github;

/**
 * GitHub Pull Request 中的一条真实提交摘要。
 *
 * @param sha Git 提交 SHA
 * @param message GitHub 返回的原始提交说明
 * @param authorName 提交作者显示名
 * @param authorUserId Qgents 用户 ID；GitHub 作者未建立受控身份映射时为 null
 * @param committedAt GitHub 提交时间（ISO-8601 UTC）
 */
public record GitHubPullRequestCommitDetails(String sha, String message, String authorName,
                                             String authorUserId, String committedAt) {
}
