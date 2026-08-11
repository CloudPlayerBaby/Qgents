package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateTeamRequest {
    @NotBlank @Size(max = 255)
    private String name;
}
