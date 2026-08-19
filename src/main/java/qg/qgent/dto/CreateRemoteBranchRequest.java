package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 从已有远程分支创建新远程分支的请求。Git SHA 由后端根据 fromRef 解析，客户端不得直接提交 SHA。
 */
@Data
@Schema(description = "创建 GitHub 远程分支请求")
public class CreateRemoteBranchRequest {
    @NotBlank
    @Size(max = 255)
    @Schema(description = "新分支名", example = "develop", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @NotBlank
    @Size(max = 255)
    @Schema(description = "来源分支名", example = "main", requiredMode = Schema.RequiredMode.REQUIRED)
    private String fromRef;
}
