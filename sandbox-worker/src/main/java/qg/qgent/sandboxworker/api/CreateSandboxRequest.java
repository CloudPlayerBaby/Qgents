package qg.qgent.sandboxworker.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * 控制层申请创建沙箱时提交的请求。
 * 请求只包含资源标识和受控配置，不允许携带宿主机路径、Docker 参数或凭证。
 */
@Data
public class CreateSandboxRequest {

    /** 由控制层生成的 Sandbox 唯一编号；重复编号会被拒绝。 */
    @NotNull
    private UUID sandboxId;

    /** 使用该沙箱的任务运行编号。 */
    @NotNull
    private UUID taskRunId;

    /** Workspace 的不透明存储键，由 Worker 解析为受控根目录下的实际路径。 */
    @NotBlank
    @Size(max = 512)
    @Pattern(regexp = "workspaces/[0-9a-fA-F-]{36}")
    private String workspaceStorageKey;

    /** Worker 白名单中的镜像配置名称，例如 java-node。 */
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "[a-z0-9][a-z0-9-]*")
    private String imageProfile;

    /** 可选的资源限制；Worker 会再次按本地上限收紧。 */
    @Valid
    private ResourceLimitsRequest limits;

    /**
     * 项目仓库编号到 Workspace 内相对目录的映射。
     * 控制层根据 workspace_repositories 生成，工具执行时通过 repositoryId 选择工作目录。
     */
    @NotNull
    @Size(max = 32)
    private List<@NotNull UUID> repositoryIds = List.of();
}
