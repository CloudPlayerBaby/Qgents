package qg.qgent.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

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
