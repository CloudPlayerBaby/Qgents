package qg.qgent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * 将团队已授权的 GitHub 仓库绑定到项目的请求。
 */
@Data
public class BindProjectRepositoryRequest {
    /**
     * 团队的 GitHub App 安装记录 ID。
     */
    @NotNull
    private UUID installationId;

    /**
     * 要绑定的 GitHub 仓库镜像 ID。
     */
    @NotNull
    private UUID repositoryId;

    /**
     * 项目使用的默认分支；为空时使用 GitHub 仓库默认分支。
     */
    @Size(max = 512)
    private String defaultBranch;

    /**
     * 仓库在项目内的显示名称。
     */
    @Size(max = 255)
    private String displayName;
}
