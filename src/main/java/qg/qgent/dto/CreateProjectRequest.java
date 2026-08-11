package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CreateProjectRequest {
    @NotBlank
    @Size(max = 255)
    private String name;
    @Size(max = 16000)
    private String description;
    @Size(max = 100)
    private List<@NotNull UUID> memberIds;
}
