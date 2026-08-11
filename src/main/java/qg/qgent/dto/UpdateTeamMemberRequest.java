package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateTeamMemberRequest {
    @NotBlank
    private String role;
}
