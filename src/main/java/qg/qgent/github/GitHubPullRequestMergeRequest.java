package qg.qgent.github;

/**
 * Server-side payload for GitHub's Pull Request merge endpoint.
 */
public record GitHubPullRequestMergeRequest(
        /* Optional title for the resulting commit. */
        String commitTitle,
        /* Optional body for the resulting commit. */
        String commitMessage,
        /* Requested GitHub merge method, for example merge, squash or rebase. */
        String mergeMethod,
        /* Expected PR head SHA, preventing a merge after an unseen source-branch update. */
        String expectedHeadSha
) {
}
