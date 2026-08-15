package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * 发起合并前试运行请求。
 * 必须提供 repositoryId、sourceRef 与 targetBranch；taskId 可选。
 */
@Data
public class DryRunCreateRequest {
    /**
     * 项目仓库绑定ID。
     */
    @NotNull
    @Schema(description = "项目仓库绑定 ID")
    private UUID repositoryId;
    /**
     * 关联 Task ID，可选。
     */
    @Schema(description = "可选关联 Task ID")
    private UUID taskId;
    /**
     * 源分支或提交引用。
     */
    @NotBlank
    @Schema(description = "源分支或 Commit 引用")
    private String sourceRef;
    /**
     * 目标分支名。
     */
    @NotBlank
    @Schema(description = "目标分支")
    private String targetBranch;
}
