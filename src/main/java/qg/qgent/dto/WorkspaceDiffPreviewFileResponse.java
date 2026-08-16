package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workspace 实时 Diff Preview 的结构化文件条目（阶段 D/E）：从受控 patch 文本解析出的
 * 单文件摘要，changeType 枚举与正式 Diff 一致（ADDED/MODIFIED/DELETED/RENAMED）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceDiffPreviewFileResponse {
    private String path;
    private String changeType;
    private Integer additions;
    private Integer deletions;
    private Boolean binary;
}
