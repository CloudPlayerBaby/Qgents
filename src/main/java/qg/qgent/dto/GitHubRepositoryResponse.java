package qg.qgent.dto;

import java.time.Instant;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GitHub App 已授权仓库的镜像元数据。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubRepositoryResponse {
    /** Qgents 仓库镜像 ID。 */
    private UUID id;

    /** GitHub 提供的仓库数字 ID。 */
    private long providerRepositoryId;

    /** GitHub 仓库所有者登录名。 */
    private String ownerLogin;

    /** GitHub 仓库名称。 */
    private String name;

    /** GitHub 仓库默认分支。 */
    private String defaultBranch;

    /** GitHub 仓库可见性。 */
    private String visibility;

    /** GitHub 是否已归档。 */
    private boolean archived;

    /** 仓库元数据最近同步时间，UTC。 */
    private Instant syncedAt;
}
