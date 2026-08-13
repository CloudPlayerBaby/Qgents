package qg.qgent.dto;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 团队已授权的 GitHub App 安装记录。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubInstallationResponse {
    /** Qgents 安装记录 ID。 */
    private UUID id;

    /** GitHub 提供的安装数字 ID。 */
    private long providerInstallationId;

    /** GitHub 授权账号登录名。 */
    private String accountLogin;

    /** GitHub 授权账号类型。 */
    private String accountType;

    /** 安装状态。 */
    private String status;

    /** 安装创建时间，UTC。 */
    private LocalDateTime installedAt;

    /** 安装记录最近同步时间，UTC。 */
    private LocalDateTime metadataSyncedAt;
}
