package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workspace 实时 Diff Preview 详情响应（阶段 D/E）：一条不可变修订的元数据与受控 patch 文本。
 * 与正式 Diff 严格分离：只反映 Coding 写过程中的累积工作树变更，永不被当作已 commit/push/MR。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceDiffPreviewResponse {
    private String projectId;
    private String taskId;
    private String taskRunId;
    private String workspaceId;
    private Long revision;
    private String baseCommit;
    private String workingTreeHash;
    private Integer filesChanged;
    private Integer additions;
    private Integer deletions;
    /**
     * 受控 patch 文本（只读预览）；快照已清理或读取失败时为 null。
     */
    private String patch;
    private String createdAt;
}
