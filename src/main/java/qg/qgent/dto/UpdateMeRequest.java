package qg.qgent.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateMeRequest {
    @Size(min = 1, max = 120)
    private String displayName;
    @Size(max = 2048)
    private String avatarUrl;
}
