package qg.qgent.github;

/**
 * GitHub Pull Request data required to mirror a repository-scoped Qgents MR.
 */
public record GitHubPullRequestDetails(
        /* GitHub's immutable Pull Request database identifier. */
        long id,
        /* Repository-local Pull Request number. */
        int number,
        /* GitHub state such as open or closed. */
        String state,
        /* Pull Request title. */
        String title,
        /* Current source-branch commit SHA. */
        String headSha,
        /* Current source branch ref. */
        String headBranch,
        /* Target branch ref. */
        String baseBranch,
        /* Whether GitHub reports that the Pull Request was merged. */
        boolean merged,
        /* GitHub page URL. */
        String htmlUrl,
        /* Whether GitHub can currently merge the Pull Request; null while GitHub is still computing mergeability. */
        Boolean mergeable,
        /* GitHub mergeable_state such as clean/dirty/blocked/behind/unstable/draft/unknown. */
        String mergeableState,
        /* Target branch (base) commit SHA used by GitHub for mergeability computation. */
        String baseSha
) {
}
