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

    /**
     * 创建项目时自动新建一个 GitHub 仓库并绑定；与 repositoryIds 互斥（二选一）。
     * 非空时走「事务外建仓 + 事务内绑定」路径，使项目创建完成即具备一个带默认分支的仓库。
     */
    private NewProjectRepositoryRequest newRepository;
}
