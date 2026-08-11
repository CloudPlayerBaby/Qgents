package qg.qgent.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProjectRequest {
    @Size(max = 255)
    private String name;
    @Size(max = 16000)
    private String description;
}
