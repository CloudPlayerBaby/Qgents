package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * TASK_STATUS 卡片使用的 Workspace 路径与项目仓库绑定映射。
 * 仅包含用户识别仓库所需的脱敏元数据，不包含宿主机路径、凭据或命令输出。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskStatusRepositoryMapping {
    @Schema(description = "Workspace 内一级相对目录，例如 repo-2")
    private String workspacePath;
    @Schema(description = "项目仓库绑定 ID")
    private String repositoryId;
    @Schema(description = "仓库短名称；元数据缺失时为空")
    private String name;
    @Schema(description = "Git 提供方 owner/name；元数据缺失时为空")
    private String fullName;
    @Schema(description = "Git 提供方，例如 GITHUB")
    private String provider;
    @Schema(description = "当前 Task 在该仓库固定的基线分支")
    private String baseRef;
    @Schema(description = "当前 Task 在该仓库的源分支")
    private String sourceBranch;
}
