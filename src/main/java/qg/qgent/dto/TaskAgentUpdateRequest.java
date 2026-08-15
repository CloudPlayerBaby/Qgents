package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Request to replace the assigned Agent before a step begins.
 */
@Data
public class TaskAgentUpdateRequest {
    @NotNull
    @Schema(description = "Replacement Agent identifier", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID assignedAgentId;
}
