package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.UUID;

/**
 * 在团队 GitHub App 组织账号下新建并绑定 GitHub 仓库的参数。
 * 创建项目时由 {@link CreateProjectRequest} 使用，已有项目也可通过项目级建仓接口使用。
 */
@Data
public class NewProjectRepositoryRequest {
    /**
     * 仓库名，需符合 GitHub 命名约束（小写字母、数字、-、_、.）。
     */
    @NotBlank
    @Size(max = 100)
    @Schema(description = "仓库名，需符合 GitHub 命名约束")
    private String name;

    /**
     * 仓库描述，可选。
     */
    @Size(max = 500)
    @Schema(description = "仓库描述")
    private String description;

    /**
     * 是否私有；为空时默认 true（团队内部开发）。
     */
    @Schema(description = "是否私有，默认 true")
    private Boolean isPrivate;

    /**
     * 建仓使用的 GitHub App 安装记录 ID；团队只有一个 ACTIVE 安装时可省略。
     * ORGANIZATION 使用 GitHub App；USER 使用当前 Qgents 用户的 GitHub OAuth 授权。
     */
    @Schema(description = "建仓使用的安装记录 ID，团队仅一个 ACTIVE 安装时可省略")
    private UUID installationId;

    /**
     * 仓库在项目内的显示名称；为空时默认取仓库名。
     */
    @Size(max = 255)
    @Schema(description = "项目内显示名称，默认取仓库名")
    private String displayName;
}
