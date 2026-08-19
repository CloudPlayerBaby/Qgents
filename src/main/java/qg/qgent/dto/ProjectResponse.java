package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 项目视图。构造器为显式 8 参（id/teamId/name/description/role/status/memberCount/repositoryCount），
 * lastActivityAt 通过 setter 填充，避免历史调用因字段追加而编译失败。
 */
@Data
@NoArgsConstructor
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

    /**
     * 项目最后活跃时间（ISO8601 UTC）：该项目下所有群中最近一条消息时间的最大值；
     * 任何群都无消息时为 null（沉底）。供「按最后活跃排序」的项目列表返回。
     */
    @Schema(description = "项目最后活跃时间（ISO8601 UTC）")
    private String lastActivityAt;

    /**
     * 项目头像 URL（OSS 公共读长期地址，可为空）。
     */
    @Schema(description = "项目头像 URL")
    private String avatarUrl;

    public ProjectResponse(String id, String teamId, String name, String description, String role, String status,
                           Long memberCount, Long repositoryCount) {
        this.id = id;
        this.teamId = teamId;
        this.name = name;
        this.description = description;
        this.role = role;
        this.status = status;
        this.memberCount = memberCount;
        this.repositoryCount = repositoryCount;
    }
}
