package qg.qgent.dto;

import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Data transfer object for quality gates configuration.
 */
@Data
public class QualityGateDto {
    private List<String> requiredChecks;
    private List<UUID> requiredTestsetIds;
}
