package qg.qgent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Request payload for updating quality gates.
 */
@Data
public class UpdateQualityGateRequest {
    @NotNull
    private List<String> requiredChecks;

    @NotNull
    private List<UUID> requiredTestsetIds;
}
