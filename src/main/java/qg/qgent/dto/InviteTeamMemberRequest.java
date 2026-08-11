package qg.qgent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class InviteTeamMemberRequest {
    @NotBlank @Email @Size(max = 320)
    private String email;
    @NotBlank
    private String role;
    @NotNull @Positive @Max(30)
    private Integer expiresInDays;
}
