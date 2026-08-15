package qg.qgent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * GitHub App 宸叉巿鏉冧粨搴撶殑闀滃儚鍏冩暟鎹€?
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubRepositoryResponse {
    /**
     * Qgents 仓库镜像 ID。
     */
    private UUID id;

    /**
     * 所属的 Qgents 安装记录 ID。
     */
    private UUID installationId;

    /**
     * GitHub 提供的仓库数字 ID。
     */
    private long providerRepositoryId;

    /**
     * 仓库全名 (owner/name)。
     */
    private String fullName;

    /**
     * GitHub 仓库主页 URL。
     */
    private String githubUrl;

    /**
     * GitHub 仓库默认分支。
     */
    private String defaultBranch;

    /**
     * GitHub 仓库可见性。
     */
    private String visibility;

    /**
     * GitHub 是否已归档。
     */
    private boolean archived;

    /**
     * 授权状态 (AUTHORIZED/REVOKED)。
     */
    private String authorizationStatus;

    /**
     * 仓库元数据最近同步时间，UTC。
     */
    private LocalDateTime metadataSyncedAt;
}
