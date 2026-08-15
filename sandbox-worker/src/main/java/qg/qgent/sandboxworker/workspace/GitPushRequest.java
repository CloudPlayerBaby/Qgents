package qg.qgent.sandboxworker.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 推送受控 sourceBranch 的请求。
 */
@Data
@Schema(description = "推送受控 sourceBranch 的请求")
public class GitPushRequest {
    /**
     * 允许推送的当前 HEAD。
     */
    @Schema(description = "期望推送的 HEAD SHA", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{40,64}")
    private String expectedHeadCommit;

    /**
     * 后端生成的一次性凭据 ID。
     */
    @Schema(description = "一次性凭据 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    @Size(max = 128)
    private String credentialGrantId;
}
