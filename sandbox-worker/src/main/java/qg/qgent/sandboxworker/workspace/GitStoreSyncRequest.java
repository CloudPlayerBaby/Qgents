package qg.qgent.sandboxworker.workspace;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 同步受控 Git Store 的请求。
 * 仅主后端可根据已绑定的 GitHub Repository 元数据构造 repositoryUrl；前端不得直接调用该接口。
 */
@Data
@Schema(description = "初始化或同步受控 bare Git Store 的请求")
public class GitStoreSyncRequest {

    /** 已绑定仓库推导出的 GitHub HTTPS 地址。 */
    @NotBlank
    @Schema(description = "已绑定仓库的 GitHub HTTPS 地址", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "https://github.com/qgents/example.git")
    private String repositoryUrl;

    /** 需要同步的远程分支。 */
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]{0,255}")
    @Schema(description = "需要同步的远程分支", requiredMode = Schema.RequiredMode.REQUIRED, example = "main")
    private String remoteBranch;

    /** 同步后远程分支必须指向的提交。 */
    @NotBlank
    @Pattern(regexp = "[0-9a-fA-F]{40,64}")
    @Schema(description = "预期远程分支 HEAD SHA", requiredMode = Schema.RequiredMode.REQUIRED)
    private String expectedHeadCommit;

    /** 后端签发的一次性 Git 凭据授权。 */
    @NotBlank
    @Size(max = 128)
    @Schema(description = "一次性 Git 凭据授权 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String credentialGrantId;
}
