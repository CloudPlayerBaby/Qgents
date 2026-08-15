package qg.qgent.sandboxworker.workspace;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/**
 * Workspace Manager 返回的持久开发现场状态。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceResponse {
    private UUID id;
    private UUID projectId;
    private String storageKey;
    private String status;
    private List<WorkspaceRepositoryResponse> repositories;
    private String createdAt;
    private String updatedAt;
}
