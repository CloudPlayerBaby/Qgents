package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workspace 实时 Diff Preview 的结构化文件条目（阶段 D/E）：从受控 patch 文本解析出的
 * 单文件摘要，changeType 枚举与正式 Diff 一致（ADDED/MODIFIED/DELETED/RENAMED）。
 * repositoryPath 为该文件所属仓库在 Workspace 内的相对目录（多仓库 patch 以
 * {@code ===== repositoryPath =====} 分隔归属；单仓库或无分隔时为空）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceDiffPreviewFileResponse {
    /**
     * 项目仓库绑定 ID（project_repositories.id）。多仓库 Preview 中用于把
     * Workspace 内的 repositoryPath 映射回真实仓库；旧快照无法解析时可为空。
     */
    private String repositoryId;
    private String path;
    private String changeType;
    private Integer additions;
    private Integer deletions;
    private Boolean binary;
    private String repositoryPath;
}
