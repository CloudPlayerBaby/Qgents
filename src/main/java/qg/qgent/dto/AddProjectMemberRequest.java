package qg.qgent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class AddProjectMemberRequest {
    @NotNull
    private UUID userId;
}
