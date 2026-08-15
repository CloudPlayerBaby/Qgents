package qg.qgent.orchestration.worker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单个文件在索引与工作树中的变更状态（镜像 Worker 的 GitChange）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerGitChange {

    /**
     * 索引状态。
     */
    private String indexStatus;

    /**
     * 工作树状态。
     */
    private String worktreeStatus;

    /**
     * 相对路径。
     */
    private String path;

    /**
     * 重命名前的原始路径，非重命名时为 null。
     */
    private String originalPath;
}
