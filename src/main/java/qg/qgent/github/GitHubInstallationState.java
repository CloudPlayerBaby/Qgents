package qg.qgent.github;

import java.util.UUID;

/** Verified context carried by a GitHub App installation state token. */
public record GitHubInstallationState(UUID teamId, GitHubClient client) {
    public GitHubInstallationState {
        if (teamId == null) {
            throw new IllegalArgumentException("teamId must not be null");
        }
        client = client == null ? GitHubClient.WEB : client;
    }
}
