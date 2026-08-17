package qg.qgent.orchestration.worker;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.UUID;

/**
 * Worker {@code /internal/v1/git-stores/{repositoryId}/sync} 返回的同步结果，镜像 Worker 的
 * GitStoreSyncResponse。主后端以仓库级返回值校验同步是否命中预期分支与 HEAD，不再忽略同步结果。
 */
@Data
@Accessors(chain = true)
public class WorkerGitStoreSyncResponse {
    /**
     * 已同步的仓库编号，须与请求的 repositoryId 对齐。
     */
    private UUID repositoryId;

    /**
     * 已同步的远程分支，须与请求的 remoteBranch 对齐。
     */
    private String remoteBranch;

    /**
     * 已核验的远程 HEAD SHA，须与请求的 expectedHeadCommit 对齐。
     */
    private String headCommit;
}
