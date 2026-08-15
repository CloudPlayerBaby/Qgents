package qg.qgent.sandboxworker.workspace;

import lombok.AllArgsConstructor;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 可审查的不可截断 Git Diff 快照。
 */
@Data
@AllArgsConstructor
@Schema(description = "可审查且未截断的 Git Diff 快照")
public class GitDiffResponse {
    /**
     * Workspace creation base commit.
     */
    private String baseCommit;
    /**
     * 快照 HEAD。
     */
    @Schema(description = "生成 Diff 时的 HEAD SHA")
    private String headCommit;
    /**
     * patch 哈希。
     */
    @Schema(description = "完整 patch 的 sha256 哈希")
    private String diffHash;
    /**
     * 完整 binary patch。
     */
    @Schema(description = "git diff --binary 输出")
    private String patch;
    /**
     * Structured file summaries; the complete immutable content remains patch.
     */
    private List<GitDiffFileResponse> files;
}
