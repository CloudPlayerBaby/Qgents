package qg.qgent.dto;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class GitHubRepositoryDtos {
    private GitHubRepositoryDtos() { }

    public record InstallationUrlResponse(String installationUrl, Instant expiresAt) { }
    public record InstallationResponse(UUID id, long providerInstallationId, String accountLogin, String accountType,
                                       String status, Instant updatedAt) { }
    public record RepositoryResponse(UUID id, long providerRepositoryId, String ownerLogin, String name,
                                     String defaultBranch, String visibility, boolean archived, Instant syncedAt) { }
    public record ProjectRepositoryResponse(UUID id, UUID repositoryId, String defaultBranch, String displayName,
                                            Instant boundAt) { }

    public record BindProjectRepositoryRequest(
            @NotNull UUID repositoryId,
            @Size(max = 512) String defaultBranch,
            @Size(max = 255) String displayName) { }

    public record UpdateProjectRepositoryRequest(
            @NotBlank @Size(max = 512) String defaultBranch,
            @Size(max = 255) String displayName) { }
}
