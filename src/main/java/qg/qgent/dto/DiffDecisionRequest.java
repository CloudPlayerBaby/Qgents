package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** User decision for an immutable Task Diff snapshot. */
@Data
public class DiffDecisionRequest {
    /** Optional acceptance note; required by the service when rejecting. */
    @Size(max = 4000)
    @Schema(description = "Review reason; required for rejection", maxLength = 4000)
    private String reason;
}
