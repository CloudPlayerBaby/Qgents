package qg.qgent.sandboxworker.workspace;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * 准备 Workspace 时单个独立仓库副本的声明。
 */
@Data
public class WorkspaceRepositoryRequest {
    /** 项目仓库绑定编号，同时用于解析共享 Git Store。 */
    @NotNull
    private UUID repositoryId;

    /** 创建独立仓库使用的基线提交或受控引用。 */
    @NotBlank
    @Size(max = 256)
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]*")
    private String baseRef;

    /** Workspace 内要创建或复用的功能分支。 */
    @NotBlank
    @Size(max = 256)
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._/-]*")
    private String sourceBranch;

    /** Workspace 内的一级相对目录名称。 */
    @NotBlank
    @Size(max = 128)
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]*")
    private String workspacePath;
}
