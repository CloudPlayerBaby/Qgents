package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RefreshTokenRequest {
    @NotBlank
    @Size(max = 512)
    private String refreshToken;
}
