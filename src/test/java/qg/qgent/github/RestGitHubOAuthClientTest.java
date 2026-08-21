package qg.qgent.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import qg.qgent.api.ApiException;
import qg.qgent.config.GitHubOAuthProperties;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestGitHubOAuthClientTest {
    private RestGitHubOAuthClient client;
    private MockRestServiceServer oauthServer;
    private MockRestServiceServer apiServer;

    @BeforeEach
    void setUp() {
        RestClient.Builder oauthBuilder = RestClient.builder();
        RestClient.Builder apiBuilder = RestClient.builder().baseUrl("https://api.github.com");
        oauthServer = MockRestServiceServer.bindTo(oauthBuilder).build();
        apiServer = MockRestServiceServer.bindTo(apiBuilder).build();

        GitHubOAuthProperties properties = new GitHubOAuthProperties();
        properties.setEnabled(true);
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret");
        properties.setCallbackUrl("https://qgents.example.com/oauth/callback");
        properties.setStateSecret("state-secret");
        properties.setTokenEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
        client = new RestGitHubOAuthClient(oauthBuilder.build(), apiBuilder.build(), properties);
    }

    @Test
    void exchangesCodeAndParsesScopes() {
        oauthServer.expect(once(), requestTo("https://github.com/login/oauth/access_token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"client_id\":\"client-id\",\"client_secret\":\"client-secret\",\"code\":\"one-time-code\",\"redirect_uri\":\"https://qgents.example.com/oauth/callback\"}"))
                .andRespond(withSuccess("{\"access_token\":\"user-token\",\"scope\":\"repo,read:user\"}", MediaType.APPLICATION_JSON));

        GitHubOAuthClient.OAuthToken token = client.exchangeCode("one-time-code");

        assertEquals("user-token", token.accessToken());
        assertEquals(java.util.List.of("repo", "read:user"), token.scopes());
        oauthServer.verify();
    }

    @Test
    void createsPersonalRepositoryWithOAuthBearerToken() {
        apiServer.expect(once(), requestTo("https://api.github.com/user/repos"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer user-token"))
                .andExpect(content().json("{\"name\":\"private-repo\",\"description\":\"desc\",\"private\":true,\"auto_init\":true}"))
                .andRespond(withSuccess("{\"id\":9,\"owner\":{\"login\":\"octocat\"},\"name\":\"private-repo\",\"default_branch\":\"main\",\"visibility\":\"private\",\"archived\":false}", MediaType.APPLICATION_JSON));

        GitHubRepositoryDetails details = client.createPersonalRepository("user-token",
                new GitHubRepositoryCreateRequest("private-repo", "desc", true, true));

        assertEquals("octocat", details.getOwnerLogin());
        assertEquals("main", details.getDefaultBranch());
        apiServer.verify();
    }

    @Test
    void revokesGrantThroughGitHubApi() {
        apiServer.expect(once(), requestTo("https://api.github.com/applications/client-id/grant"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Basic Y2xpZW50LWlkOmNsaWVudC1zZWNyZXQ="))
                .andExpect(content().json("{\"access_token\":\"user-token\"}"))
                .andRespond(withSuccess());

        client.revokeAccessToken("user-token");

        apiServer.verify();
    }

    @Test
    void revokeRejects401InsteadOfTreatingAsSuccess() {
        apiServer.expect(once(), requestTo("https://api.github.com/applications/client-id/grant"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ApiException exception = assertThrows(ApiException.class, () -> client.revokeAccessToken("user-token"));
        assertEquals("GITHUB_OAUTH_REVOKE_REJECTED", exception.code());
        apiServer.verify();
    }

    @Test
    void revokeTreats404AsAlreadyRevoked() {
        apiServer.expect(once(), requestTo("https://api.github.com/applications/client-id/grant"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        client.revokeAccessToken("user-token");

        apiServer.verify();
    }

    @Test
    void createPersonalRepositoryMaps401ToRevoked() {
        apiServer.expect(once(), requestTo("https://api.github.com/user/repos"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        ApiException exception = assertThrows(ApiException.class, () -> client.createPersonalRepository("user-token",
                new GitHubRepositoryCreateRequest("private-repo", "desc", true, true)));
        assertEquals("GITHUB_OAUTH_REVOKED", exception.code());
        apiServer.verify();
    }

    @Test
    void createPersonalRepositoryMaps403ToForbidden() {
        apiServer.expect(once(), requestTo("https://api.github.com/user/repos"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        ApiException exception = assertThrows(ApiException.class, () -> client.createPersonalRepository("user-token",
                new GitHubRepositoryCreateRequest("private-repo", "desc", true, true)));
        assertEquals("GITHUB_PERSONAL_REPOSITORY_CREATE_FORBIDDEN", exception.code());
        apiServer.verify();
    }

    @Test
    void createPersonalRepositoryMaps429ToUpstreamUnavailable() {
        apiServer.expect(once(), requestTo("https://api.github.com/user/repos"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        ApiException exception = assertThrows(ApiException.class, () -> client.createPersonalRepository("user-token",
                new GitHubRepositoryCreateRequest("private-repo", "desc", true, true)));
        assertEquals("GITHUB_OAUTH_UPSTREAM_UNAVAILABLE", exception.code());
        apiServer.verify();
    }

    @Test
    void createPersonalRepositoryMaps5xxToUpstreamUnavailable() {
        apiServer.expect(once(), requestTo("https://api.github.com/user/repos"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        ApiException exception = assertThrows(ApiException.class, () -> client.createPersonalRepository("user-token",
                new GitHubRepositoryCreateRequest("private-repo", "desc", true, true)));
        assertEquals("GITHUB_OAUTH_UPSTREAM_UNAVAILABLE", exception.code());
        apiServer.verify();
    }
}
