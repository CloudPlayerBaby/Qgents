package qg.qgent.dto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.util.*;
/** Request to create one task from an active requirement-group conversation. */
@Data public class TaskCreateRequest {
 @NotNull @Schema(description="Active REQUIREMENT group identifier",requiredMode=Schema.RequiredMode.REQUIRED) private UUID requirementGroupId;
 @Schema(description="Optional triggering message identifier") private UUID triggerMessageId;
 @NotBlank @Size(max=255) @Schema(description="Task title",maxLength=255,requiredMode=Schema.RequiredMode.REQUIRED) private String title;
 @NotBlank @Size(max=10000) @Schema(description="Requirement snapshot",maxLength=10000,requiredMode=Schema.RequiredMode.REQUIRED) private String requirement;
 @NotEmpty @Size(max=20) @Schema(description="Project repository bindings available to the task",requiredMode=Schema.RequiredMode.REQUIRED) private List<@NotNull UUID> repositoryIds;
 @Size(max=512) @Schema(description="Optional common base ref") private String baseRef;
}
