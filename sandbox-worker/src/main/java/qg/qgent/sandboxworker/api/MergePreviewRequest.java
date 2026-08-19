package qg.qgent.sandboxworker.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * 只读合并预演请求。
 */
@Data
public class MergePreviewRequest {
    @NotNull
    private UUID repositoryId;
    @NotBlank
    @Size(max = 512)
    private String sourceRef;
    @NotBlank
    @Size(max = 512)
    /**
     * 主后端在受理 Dry Run 时已经固定的目标提交 SHA，不接受可变分支名。
     */
    private String targetCommit;
}
