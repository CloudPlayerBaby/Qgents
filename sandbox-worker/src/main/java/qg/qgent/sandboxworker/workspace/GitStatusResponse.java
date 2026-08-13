package qg.qgent.sandboxworker.workspace;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

/** Workspace 仓库的结构化 Git 状态。 */
@Data
@AllArgsConstructor
@Schema(description = "Workspace 仓库的结构化 Git 状态")
public class GitStatusResponse {
    /** 当前分支。 */ @Schema(description = "当前 source branch")
    private String branch;
    /** 当前 HEAD。 */ @Schema(description = "当前 HEAD SHA")
    private String headCommit;
    /** 是否无变更。 */ @Schema(description = "工作树与暂存区是否干净")
    private boolean clean;
    /** 结构化变更。 */ @Schema(description = "tracked 与 untracked 变更")
    private List<GitChangeResponse> changes;
}
