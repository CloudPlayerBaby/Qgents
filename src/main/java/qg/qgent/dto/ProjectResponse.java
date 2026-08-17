package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectResponse {
    private String id;
    private String teamId;
    private String name;
    private String description;
    private String role;
    private String status;

    /**
     * 项目成员数（project_members 计数），项目列表/详情接口返回。
     */
    @Schema(description = "项目成员数")
    private Long memberCount;

    /**
     * 项目当前生效的仓库绑定数（project_repositories 中 status=ACTIVE），项目列表/详情接口返回；
     * 软解绑（UNBOUND）仓库不计入。
     */
    @Schema(description = "项目当前生效的仓库绑定数")
    private Long repositoryCount;
}
