package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Planner-produced workflow step definition.
 */
@Data
public class TaskStepCreateRequest {
    @NotNull
    @Schema(description = "Client-generated UUID used by dependencyIds", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID id;
    @NotBlank
    @Size(max = 255)
    @Schema(description = "Step title", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;
    @NotBlank
    @Size(max = 10000)
    @Schema(description = "Exact Agent instruction", requiredMode = Schema.RequiredMode.REQUIRED)
    private String instruction;
    @NotBlank
    @Size(max = 32)
    @Schema(description = "Required Agent role", requiredMode = Schema.RequiredMode.REQUIRED)
    private String role;
    @Schema(description = "Agent selected for this step")
    private UUID assignedAgentId;
    @Size(max = 5000)
    @Schema(description = "Acceptance criteria")
    private String acceptanceCriteria;
    @Schema(description = "Identifiers of earlier steps in this Task")
    private List<@NotNull UUID> dependencyIds;
    @NotEmpty
    @Schema(description = "Per-repository access scopes")
    private List<@Valid TaskRepositoryScopeRequest> repositoryScopes;
}
