package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Workspace 与 Sandbox 的只读状态摘要。
 * 不返回宿主机路径、容器控制入口或任何凭据；未由执行服务填充的字段为 null。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionContextResponse {
    @Schema(description = "项目 Workspace ID；运行未关联 Workspace 时为 null")
    private String workspaceId;
    @Schema(description = "Sandbox 状态；当前无可观测 Sandbox 记录时为 null")
    private String sandboxStatus;
    @Schema(description = "项目仓库绑定 ID；无代码仓库时为 null")
    private String repositoryId;
    @Schema(description = "Workspace 初始化使用的基线分支；无 worktree 时为 null")
    private String baseRef;
    @Schema(description = "Task 实际工作分支；无 worktree 时为 null")
    private String headRef;
    private String startedAt;
    private String expiresAt;
}
