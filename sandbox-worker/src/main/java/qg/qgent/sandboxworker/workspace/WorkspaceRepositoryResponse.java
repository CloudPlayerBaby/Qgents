package qg.qgent.sandboxworker.workspace;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Workspace 中单个独立仓库副本的可公开状态。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceRepositoryResponse {
    private UUID repositoryId;
    private String workspacePath;
    private String sourceBranch;
    private String baseRef;
    private String headCommit;
}
