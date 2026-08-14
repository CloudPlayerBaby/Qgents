package qg.qgent.sandboxworker.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/** 只读合并预演请求。 */
@Data
public class MergePreviewRequest {
    @NotNull private UUID repositoryId;
    @NotBlank @Size(max = 512) private String sourceRef;
    @NotBlank @Size(max = 512) private String targetBranch;
}
