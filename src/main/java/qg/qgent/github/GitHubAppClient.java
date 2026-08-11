package qg.qgent.github;

import java.util.List;
import java.util.UUID;

public interface GitHubAppClient {
    String createInstallationUrl(UUID teamId, UUID actorId);

    UUID verifyInstallationState(String state);

    InstallationDetails getInstallation(long installationId);

    List<RepositoryDetails> listRepositories(long installationId);

    record InstallationDetails(long installationId, String accountLogin, String accountType) { }

    record RepositoryDetails(long repositoryId, String ownerLogin, String name, String defaultBranch,
                             String visibility, boolean archived) { }
}
