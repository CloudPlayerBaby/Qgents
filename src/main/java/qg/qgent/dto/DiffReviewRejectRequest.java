package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Reason supplied when a Task-level final Diff review is rejected.
 */
@Data
public class DiffReviewRejectRequest {
    @NotBlank
    @Size(max = 4000)
    private String reason;
}
