package qg.qgent.dto;

import java.util.List;
import java.util.UUID;

import lombok.Data;

/**
 * Data transfer object for quality gates configuration.
 */
@Data
public class QualityGateDto {
    private List<String> requiredChecks;
    private List<UUID> requiredTestsetIds;
}
