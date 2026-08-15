package qg.qgent.sandboxworker.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * 由 Worker 在受控 Git Store 中解析一个 Git 引用。
 */
@Data
public class GitResolveRequest {
    @NotNull
    private UUID repositoryId;
    @NotBlank
    @Size(max = 512)
    private String ref;
}
