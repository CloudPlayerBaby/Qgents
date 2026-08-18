package qg.qgent.github;

/**
 * GitHub 创建评论后返回的最小事实集合。
 */
public record GitHubPullRequestCommentDetails(
        long id,
        String body,
        String htmlUrl,
        String createdAt
) {
}
