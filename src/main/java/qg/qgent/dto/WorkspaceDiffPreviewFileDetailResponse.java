package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workspace 实时 Diff Preview 的单文件详情；patch 只包含当前文件。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceDiffPreviewFileDetailResponse {
    private Long revision;
    private String repositoryId;
    private String path;
    private String changeType;
    private Integer additions;
    private Integer deletions;
    private Boolean binary;
    private String patch;
}
