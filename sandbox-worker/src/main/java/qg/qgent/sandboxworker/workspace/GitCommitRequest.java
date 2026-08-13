package qg.qgent.sandboxworker.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

/** 基于已审查 Diff 创建 Commit 的请求。 */
@Data
@Schema(description = "基于已审查 Diff 创建真实 Git Commit 的请求")
public class GitCommitRequest {
    /** 审查 Diff 时的 HEAD。 */
    @Schema(description = "审查 Diff 时的 HEAD SHA", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{40,64}")
    private String expectedHeadCommit;
    /** 审查 Diff 的 SHA-256。 */
    @Schema(description = "审查 Diff 的 sha256 哈希", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Pattern(regexp = "sha256:[0-9a-f]{64}")
    private String expectedDiffHash;
    /** Conventional Commit 消息。 */
    @Schema(description = "Commit message", maxLength = 500, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 500)
    private String message;
}
