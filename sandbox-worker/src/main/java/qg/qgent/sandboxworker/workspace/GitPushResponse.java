package qg.qgent.sandboxworker.workspace;

import lombok.AllArgsConstructor;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Git Push 及远端核验结果。
 */
@Data
@AllArgsConstructor
@Schema(description = "Git Push 及远端引用核验结果")
public class GitPushResponse {
    /**
     * 推送分支。
     */
    @Schema(description = "受控 source branch")
    private String branch;
    /**
     * 推送 SHA。
     */
    @Schema(description = "推送的 HEAD SHA")
    private String headCommit;
    /**
     * 远端是否核验一致。
     */
    @Schema(description = "远端 ref 是否指向 headCommit")
    private boolean verified;
}
