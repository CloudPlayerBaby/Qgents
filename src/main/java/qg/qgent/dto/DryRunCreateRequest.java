package qg.qgent.dto;

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
    /** 项目仓库绑定ID。 */
    @NotNull
    private UUID repositoryId;
    /** 关联 Task ID，可选。 */
    private UUID taskId;
    /** 源分支或提交引用。 */
    @NotBlank
    private String sourceRef;
    /** 目标分支名。 */
    @NotBlank
    private String targetBranch;
}
