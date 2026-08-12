package qg.qgent.github;

/** Result returned by GitHub after a Pull Request merge request. */
public record GitHubPullRequestMergeResult(
        /* Whether GitHub actually merged the Pull Request. */
        boolean merged,
        /* Resulting merge commit SHA when available. */
        String sha,
        /* GitHub's human-readable merge outcome. */
        String message
) {}
