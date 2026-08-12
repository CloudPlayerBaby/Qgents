package qg.qgent.github;

/** Server-side payload for GitHub's Pull Request creation endpoint. */
public record GitHubPullRequestCreateRequest(
        /* Pull Request title shown in GitHub. */
        String title,
        /* Optional Markdown body shown in GitHub. */
        String body,
        /* Existing remote feature branch containing the controlled task commit. */
        String head,
        /* Target branch configured for the project repository. */
        String base
) {}
