package qg.qgent.github;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import qg.qgent.api.ApiException;
import qg.qgent.config.GitHubOAuthProperties;

import java.util.List;

/** GitHub OAuth REST 实现；日志不记录 code、token 或响应原文。 */
@Slf4j
public class RestGitHubOAuthClient implements GitHubOAuthClient {
    private static final String ACCEPT = "application/vnd.github+json";
    private static final String API_VERSION = "2022-11-28";
    private final RestClient oauthClient;
    private final RestClient apiClient;
    private final GitHubOAuthProperties properties;

    public RestGitHubOAuthClient(RestClient oauthClient, RestClient apiClient, GitHubOAuthProperties properties) {
        this.oauthClient = oauthClient;
        this.apiClient = apiClient;
        this.properties = properties;
    }

    @Override
    public String buildAuthorizationUrl(String state, List<String> scopes) {
        requireConfigured();
        return UriComponentsBuilder.fromUriString(properties.getAuthorizeUrl())
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getCallbackUrl())
                .queryParam("scope", String.join(" ", scopes))
                .queryParam("state", state)
                .build().toUriString();
    }

    @Override
    public OAuthToken exchangeCode(String code) {
        requireConfigured();
        try {
            TokenResponse response = oauthClient.post().uri(properties.getTokenUrl())
                    .headers(this::oauthHeaders)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new TokenRequest(properties.getClientId(), properties.getClientSecret(), code,
                            properties.getCallbackUrl()))
                    .retrieve().body(TokenResponse.class);
            if (response == null || response.accessToken() == null || response.accessToken().isBlank()) {
                throw upstream("GITHUB_OAUTH_CODE_EXCHANGE_FAILED", "GitHub OAuth code 交换失败");
            }
            return new OAuthToken(response.accessToken(), parseScopes(response.scope()));
        } catch (RestClientResponseException exception) {
            log.warn("GitHub OAuth code exchange rejected: status={}", exception.getStatusCode().value());
            throw upstream("GITHUB_OAUTH_CODE_EXCHANGE_FAILED", "GitHub OAuth code 交换失败");
        } catch (RestClientException exception) {
            log.warn("GitHub OAuth code exchange unavailable: {}", exception.getClass().getSimpleName());
            throw upstream("GITHUB_OAUTH_UPSTREAM_UNAVAILABLE", "GitHub OAuth 服务暂不可用");
        }
    }

    @Override
    public GitHubUser getCurrentUser(String accessToken) {
        try {
            UserResponse response = apiClient.get().uri(properties.getUserUrl())
                    .headers(headers -> githubHeaders(headers, accessToken)).retrieve().body(UserResponse.class);
            if (response == null || response.id() == 0 || response.login() == null || response.login().isBlank()) {
                throw upstream("GITHUB_OAUTH_ACCOUNT_LOOKUP_FAILED", "无法读取 GitHub 用户信息");
            }
            return new GitHubUser(response.id(), response.login());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == 401) {
                throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "GITHUB_OAUTH_TOKEN_INVALID", "GitHub OAuth Token 已失效，请重新授权");
            }
            log.warn("GitHub OAuth user lookup rejected: status={}", exception.getStatusCode().value());
            throw upstream("GITHUB_OAUTH_ACCOUNT_LOOKUP_FAILED", "无法读取 GitHub 用户信息");
        } catch (RestClientException exception) {
            log.warn("GitHub OAuth user lookup unavailable: {}", exception.getClass().getSimpleName());
            throw upstream("GITHUB_OAUTH_UPSTREAM_UNAVAILABLE", "GitHub OAuth 服务暂不可用");
        }
    }

    @Override
    public GitHubRepositoryDetails createPersonalRepository(String accessToken, GitHubRepositoryCreateRequest request) {
        try {
            RepositoryResponse response = apiClient.post().uri("/user/repos")
                    .headers(headers -> githubHeaders(headers, accessToken)).body(request)
                    .retrieve().body(RepositoryResponse.class);
            if (response == null || response.id() == 0 || response.owner() == null || response.name() == null) {
                throw upstream("GITHUB_API_UNAVAILABLE", "GitHub 未返回有效的仓库信息");
            }
            return new GitHubRepositoryDetails(response.id(), response.owner().login(), response.name(),
                    response.defaultBranch(), response.visibility(), response.archived());
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status == 422) {
                throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                        "GITHUB_REPOSITORY_CREATE_CONFLICT", "GitHub 仓库名称已存在或参数无效");
            }
            if (status == 401) {
                throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "GITHUB_OAUTH_REVOKED",
                        "GitHub OAuth 授权已失效，请重新授权");
            }
            if (status == 403) {
                throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN,
                        "GITHUB_PERSONAL_REPOSITORY_CREATE_FORBIDDEN", "当前用户无权创建目标个人仓库");
            }
            if (status == 429 || status >= 500) {
                log.warn("GitHub personal repository creation upstream failure: status={}", status);
                throw upstream("GITHUB_OAUTH_UPSTREAM_UNAVAILABLE", "GitHub 服务暂不可用，请稍后重试");
            }
            log.warn("GitHub personal repository creation rejected: status={}", status);
            throw upstream("GITHUB_PERSONAL_REPOSITORY_CREATE_REJECTED", "GitHub 拒绝创建个人仓库");
        } catch (RestClientException exception) {
            log.warn("GitHub personal repository creation unavailable: {}", exception.getClass().getSimpleName());
            throw upstream("GITHUB_OAUTH_UPSTREAM_UNAVAILABLE", "GitHub 服务暂不可用");
        }
    }

    @Override
    public void deletePersonalRepository(String accessToken, String owner, String repository) {
        try {
            apiClient.delete().uri("/repos/{owner}/{repository}", owner, repository)
                    .headers(headers -> githubHeaders(headers, accessToken)).retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 404) {
                throw upstream("GITHUB_OAUTH_UPSTREAM_UNAVAILABLE", "GitHub 个人仓库补偿删除失败");
            }
        } catch (RestClientException exception) {
            throw upstream("GITHUB_OAUTH_UPSTREAM_UNAVAILABLE", "GitHub 个人仓库补偿删除服务暂不可用");
        }
    }

    @Override
    public void revokeAccessToken(String accessToken) {
        try {
            apiClient.method(HttpMethod.DELETE).uri(uri -> uri.path("/applications/{clientId}/grant")
                            .build(properties.getClientId()))
                    .headers(headers -> {
                        headers.setBasicAuth(properties.getClientId(), properties.getClientSecret());
                        headers.set(HttpHeaders.ACCEPT, ACCEPT);
                        headers.set("X-GitHub-Api-Version", API_VERSION);
                    })
                    .contentType(MediaType.APPLICATION_JSON).body(new TokenRevokeRequest(accessToken))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException exception) {
            int status = exception.getStatusCode().value();
            if (status != 404) {
                if (status == 401) {
                    throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                            "GITHUB_OAUTH_REVOKE_REJECTED", "GitHub OAuth 撤销被拒绝，请检查应用配置或重新授权");
                }
                throw upstream("GITHUB_OAUTH_UPSTREAM_UNAVAILABLE", "GitHub OAuth 撤销授权失败");
            }
        } catch (RestClientException exception) {
            throw upstream("GITHUB_OAUTH_UPSTREAM_UNAVAILABLE", "GitHub OAuth 撤销授权服务暂不可用");
        }
    }

    private void requireConfigured() {
        if (!properties.configured()) throw new ApiException(org.springframework.http.HttpStatus.NOT_IMPLEMENTED,
                "GITHUB_OAUTH_NOT_CONFIGURED", "GitHub OAuth 尚未配置");
    }
    private void oauthHeaders(HttpHeaders headers) { headers.setAccept(List.of(MediaType.APPLICATION_JSON)); }
    private void githubHeaders(HttpHeaders headers, String token) {
        headers.setBearerAuth(token); headers.set(HttpHeaders.ACCEPT, ACCEPT); headers.set("X-GitHub-Api-Version", API_VERSION);
    }
    private List<String> parseScopes(String scope) {
        return scope == null || scope.isBlank() ? List.of() : java.util.Arrays.stream(scope.split("[,\\s]+"))
                .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }
    private ApiException upstream(String code, String message) { return new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, code, message); }

    private record TokenRequest(String client_id, String client_secret, String code, String redirect_uri) { }
    private record TokenRevokeRequest(@JsonProperty("access_token") String accessToken) { }
    private record TokenResponse(@JsonProperty("access_token") String accessToken, String scope) { }
    private record UserResponse(long id, String login) { }
    private record RepositoryResponse(long id, String name, @JsonProperty("default_branch") String defaultBranch,
                                      String visibility, boolean archived, Owner owner) { }
    private record Owner(String login) { }
}
