package qg.qgent.github;

import java.util.UUID;

/**
 * Verified context carried by a GitHub App installation state token.
 * <p>
 * {@code conflictCode} 非空表示本次安装回调因归属冲突未执行同步（如同一 GitHub 账号已绑定其他团队），
 * 调用方应据此重定向回前端展示明确提示，而不是把 409 抛给网关。
 */
public record GitHubInstallationState(UUID teamId, GitHubClient client, String conflictCode) {
    public GitHubInstallationState {
        if (teamId == null) {
            throw new IllegalArgumentException("teamId must not be null");
        }
        client = client == null ? GitHubClient.WEB : client;
    }

    public GitHubInstallationState(UUID teamId, GitHubClient client) {
        this(teamId, client, null);
    }

    /**
     * 返回携带冲突错误码的新状态；原状态为 null 冲突码时也正常返回。
     */
    public GitHubInstallationState withConflictCode(String code) {
        return new GitHubInstallationState(teamId, client, code);
    }
}
