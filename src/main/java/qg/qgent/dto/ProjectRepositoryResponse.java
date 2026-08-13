package qg.qgent.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 椤圭洰涓庡凡鎺堟潈 GitHub 浠撳簱涔嬮棿鐨勭粦瀹氳褰曘€?
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProjectRepositoryResponse {
    /** 项目仓库绑定 ID。 */
    private UUID id;

    /** 被绑定的 GitHub 仓库镜像 ID。 */
    private UUID repositoryId;

    /** 所属的 Qgents 安装记录 ID。 */
    private UUID installationId;

    /** GitHub 提供的仓库数字 ID。 */
    private long providerRepositoryId;

    /** 仓库全名 (owner/name)。 */
    private String fullName;

    /** GitHub 仓库主页 URL。 */
    private String githubUrl;

    /** 项目使用的默认分支。 */
    private String defaultBranch;

    /** 仓库在项目内的显示名称。 */
    private String displayName;

    /** 授权状态 (AUTHORIZED/REVOKED)。 */
    private String authorizationStatus;

    /** 仓库元数据最近同步时间，UTC。 */
    private LocalDateTime metadataSyncedAt;

    /** 绑定创建时间，UTC。 */
    private LocalDateTime boundAt;
}
