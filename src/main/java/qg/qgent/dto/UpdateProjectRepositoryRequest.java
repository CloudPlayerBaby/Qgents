package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新项目仓库绑定显示信息的请求。
 */
@Data
public class UpdateProjectRepositoryRequest {
    /**
     * 项目使用的默认分支。
     */
    @NotBlank
    @Size(max = 512)
    private String defaultBranch;

    /**
     * 仓库在项目内的显示名称。
     */
    @Size(max = 255)
    private String displayName;
}
