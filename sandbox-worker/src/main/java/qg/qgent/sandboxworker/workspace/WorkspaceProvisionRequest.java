package qg.qgent.sandboxworker.workspace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 控制层请求准备一个持久 Workspace 时提交的完整声明。
 */
@Data
public class WorkspaceProvisionRequest {
    /** Workspace 所属项目编号，用于幂等冲突校验。 */
    @NotNull
    private UUID projectId;

    /** Workspace 下需要准备的仓库 worktree。 */
    @NotEmpty
    @Size(max = 32)
    private List<@Valid WorkspaceRepositoryRequest> repositories;
}
