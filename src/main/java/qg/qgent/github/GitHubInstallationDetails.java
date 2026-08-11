package qg.qgent.github;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GitHub App 安装的服务端查询结果，不包含安装访问令牌。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GitHubInstallationDetails {
    /** GitHub 提供的安装数字 ID。 */
    private long installationId;

    /** 授权账号登录名。 */
    private String accountLogin;

    /** 授权账号类型。 */
    private String accountType;
}
