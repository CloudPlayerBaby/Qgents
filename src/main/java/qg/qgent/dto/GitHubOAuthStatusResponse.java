package qg.qgent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/** 当前用户的 GitHub OAuth 授权摘要；永不返回 access token。 */
@Data
public class GitHubOAuthStatusResponse {
    private boolean authorized;
    private String provider;
    private Long githubUserId;
    private String githubLogin;
    private List<String> scopes = List.of();
    private OffsetDateTime authorizedAt;
    private OffsetDateTime lastValidatedAt;
    @Schema(description = "scope 是否允许创建公开个人仓库（repo 或 public_repo）")
    private boolean canCreatePublicPersonalRepository;
    @Schema(description = "scope 是否允许创建私有个人仓库（需要 repo）")
    private boolean canCreatePrivatePersonalRepository;
    @Schema(description = "个人仓库开通前置状态：NOT_OWNER / NEED_INSTALLATION / NEED_OAUTH / ACCOUNT_MISMATCH / READY")
    private String personalRepositorySetup;
    @Schema(description = "账号不一致时提示应使用的 GitHub App 安装账号 login（仅 ACCOUNT_MISMATCH 时有值）")
    private String expectedInstallationLogin;
}
