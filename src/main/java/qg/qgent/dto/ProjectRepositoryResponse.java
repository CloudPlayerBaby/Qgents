package qg.qgent.dto;

import java.time.Instant;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 项目与已授权 GitHub 仓库之间的绑定记录。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectRepositoryResponse {
    /** 项目仓库绑定 ID。 */
    private UUID id;

    /** 被绑定的 GitHub 仓库镜像 ID。 */
    private UUID repositoryId;

    /** 项目使用的默认分支。 */
    private String defaultBranch;

    /** 仓库在项目内的显示名称。 */
    private String displayName;

    /** 绑定创建时间，UTC。 */
    private Instant boundAt;
}
