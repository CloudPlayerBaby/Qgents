package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetPasswordRequest {
    @NotBlank
    @Size(max = 512)
    private String token;
    @NotBlank
    @Size(max = 128)
    private String passwordKeyId;
    @NotBlank
    @Size(max = 4096)
    private String newPassword;
}
