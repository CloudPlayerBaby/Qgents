package qg.qgent.orchestration.worker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Worker 返回的完整工作树 Diff（镜像 Worker 的 GitDiff）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerGitDiff {

    /**
     * Compatibility constructor for the original Worker response shape.
     */
    public WorkerGitDiff(String headCommit, String diffHash, String patch) {
        this.headCommit = headCommit;
        this.diffHash = diffHash;
        this.patch = patch;
    }

    /**
     * Immutable base commit selected when the Workspace repository was created.
     */
    private String baseCommit;

    /**
     * 生成 Diff 时的 HEAD 提交。
     */
    private String headCommit;

    /**
     * 包含未跟踪文件的完整 patch 摘要哈希。
     */
    private String diffHash;

    /**
     * 完整 patch 文本。
     */
    private String patch;

    private List<WorkerGitDiffFile> files;
}
