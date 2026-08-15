package qg.qgent.sandboxworker.workspace;

import lombok.AllArgsConstructor;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Git 工作树中的单项结构化变更。
 */
@Data
@AllArgsConstructor
@Schema(description = "Git 工作树中的单项变更")
public class GitChangeResponse {
    /**
     * 暂存区状态码。
     */
    @Schema(description = "porcelain X 状态码")
    private String indexStatus;
    /**
     * 工作树状态码。
     */
    @Schema(description = "porcelain Y 状态码")
    private String worktreeStatus;
    /**
     * 当前相对路径。
     */
    @Schema(description = "仓库相对路径")
    private String path;
    /**
     * rename/copy 之前的相对路径。
     */
    @Schema(description = "rename/copy 原路径", nullable = true)
    private String originalPath;
}
