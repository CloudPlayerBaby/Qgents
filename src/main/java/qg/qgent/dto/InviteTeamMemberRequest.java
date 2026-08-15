package qg.qgent.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class InviteTeamMemberRequest {
    @NotBlank
    @Email
    @Size(max = 320)
    private String email;
    @NotBlank
    private String role;
    @NotNull
    @Positive
    @Max(30)
    private Integer expiresInDays;
}
