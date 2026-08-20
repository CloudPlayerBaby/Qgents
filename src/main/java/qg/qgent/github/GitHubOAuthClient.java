package qg.qgent.github;

import java.util.List;

/** GitHub OAuth 用户授权与个人仓库创建的受控 HTTP 端口。 */
public interface GitHubOAuthClient {
    String buildAuthorizationUrl(String state, List<String> scopes);
    OAuthToken exchangeCode(String code);
    GitHubUser getCurrentUser(String accessToken);
    GitHubRepositoryDetails createPersonalRepository(String accessToken, GitHubRepositoryCreateRequest request);
    void deletePersonalRepository(String accessToken, String owner, String repository);
    void revokeAccessToken(String accessToken);

    record OAuthToken(String accessToken, List<String> scopes) { }
    record GitHubUser(long id, String login) { }
}
