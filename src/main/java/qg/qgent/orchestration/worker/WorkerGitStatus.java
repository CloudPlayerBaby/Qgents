package qg.qgent.orchestration.worker;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Worker 返回的仓库当前分支、HEAD 与结构化变更（镜像 Worker 的 GitStatus）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkerGitStatus {

    /** 当前分支。 */
    private String branch;

    /** 当前 HEAD 提交。 */
    private String headCommit;

    /** 工作树是否干净。 */
    private Boolean clean;

    /** 结构化变更列表。 */
    private List<WorkerGitChange> changes;
}
