package qg.qgent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class CreateProjectRequest {
    @NotBlank
    @Size(max = 255)
    private String name;
    @Size(max = 16000)
    private String description;
    @Size(max = 100)
    private List<@NotNull UUID> memberIds;

    /**
     * 创建项目时一并绑定的 GitHub 授权仓库 id 列表（github_repositories.id，授权仓本地 UUID）；
     * 可空，为空则创建后单独调用绑定接口。
     */
    @Size(max = 50)
    private List<@NotNull UUID> repositoryIds;
}
