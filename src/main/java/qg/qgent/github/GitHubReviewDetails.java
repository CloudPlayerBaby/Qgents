package qg.qgent.github;

/** Minimal GitHub Pull Request review summary for later CQ mapping. */
public record GitHubReviewDetails(
        /* GitHub review identifier. */
        long id,
        /* GitHub review decision state. */
        String state,
        /* Reviewer's repository relationship reported by GitHub. */
        String authorAssociation,
        /* GitHub login of the reviewer. */
        String userLogin
) {}
