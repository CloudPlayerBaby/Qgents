package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.UUID;

/**
 * Repository-specific access granted to one TaskStep.
 */
@Data
public class TaskRepositoryScopeRequest {
    /**
     * Project repository binding identifier.
     */
    @NotNull
    @Schema(description = "Project repository binding identifier", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID repositoryId;
    /**
     * Access mode for this repository only.
     */
    @NotNull
    @Pattern(regexp = "READ|WRITE")
    @Schema(description = "Repository access mode", allowableValues = {"READ", "WRITE"})
    private String accessMode;
}
