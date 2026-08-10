package qg.qgent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank @Email @Size(max = 320)
    private String email;
    @NotBlank @Size(max = 128)
    private String passwordKeyId;
    @NotBlank @Size(max = 4096)
    private String password;
}
