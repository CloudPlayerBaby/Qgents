package qg.qgent.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import qg.qgent.config.GitHubAppProperties;

class RestGitHubAppClientTest {

    private RestGitHubAppClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.github.com");
        server = MockRestServiceServer.bindTo(builder).build();
        GitHubAppProperties properties = new GitHubAppProperties();
        properties.setAppId("1");
        properties.setSlug("qgents");
        properties.setPrivateKeyPath("test-only-not-read.pem");
        properties.setCallbackUrl("https://qgents.example.com/callback");
        properties.setStateSecret("test-state-secret");
        client = new RestGitHubAppClient(builder.build(), properties, Clock.systemUTC(), ignored -> "test-token");
    }

    @Test
    void createsPullRequestWithInstallationToken() {
        GitHubPullRequestCreateRequest request = new GitHubPullRequestCreateRequest(
                "Test PR",
                "This is a mock PR",
                "feat/mock",
                "main"
        );
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/pulls"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        {"id":12345,"number":42,"state":"open","title":"Test PR",
                         "html_url":"https://github.com/owner/repo/pull/42",
                         "head":{"ref":"feat/mock","sha":"head-sha"},"base":{"ref":"main","sha":"base-sha"}}
                        """, MediaType.APPLICATION_JSON));

        GitHubPullRequestDetails details = client.createPullRequest(12345L, "owner", "repo", request);
        assertEquals("open", details.state());
        assertEquals("Test PR", details.title());
        assertEquals("main", details.baseBranch());
        assertEquals("head-sha", details.headSha());
        server.verify();
    }

    @Test
    void getsPullRequest() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/pulls/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":12345,"number":42,"state":"open","title":"Test PR",
                         "html_url":"https://github.com/owner/repo/pull/42",
                         "head":{"ref":"feat/mock","sha":"head-sha"},"base":{"ref":"main","sha":"base-sha"}}
                        """, MediaType.APPLICATION_JSON));
        GitHubPullRequestDetails details = client.getPullRequest(12345L, "owner", "repo", 42);
        assertEquals(42, details.number());
        assertEquals("open", details.state());
        server.verify();
    }

    @Test
    void getsCheckRunsForHeadCommit() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/commits/sha123/check-runs?per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"check_runs":[{"id":1001,"name":"TESTSET","status":"completed","conclusion":"success"},
                        {"id":1002,"name":"AI_REVIEW","status":"completed","conclusion":"success"}]}
                        """, MediaType.APPLICATION_JSON));
        List<GitHubCheckRunDetails> checks = client.getPullRequestChecks(12345L, "owner", "repo", "sha123");
        assertEquals(2, checks.size());
        assertEquals("TESTSET", checks.getFirst().name());
        server.verify();
    }

    @Test
    void getsPullRequestReviews() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/pulls/42/reviews?per_page=100"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"id":2001,"state":"APPROVED","author_association":"MEMBER","user":{"login":"reviewer-user"}}]
                        """, MediaType.APPLICATION_JSON));
        List<GitHubReviewDetails> reviews = client.getPullRequestReviews(12345L, "owner", "repo", 42);
        assertEquals(1, reviews.size());
        assertEquals("APPROVED", reviews.get(0).state());
        server.verify();
    }

    @Test
    void returnsGitHubMergeOutcomeInsteadOfAssumingSuccess() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/pulls/42/merge"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess("""
                        {"sha":"merge-sha","merged":false,"message":"Pull Request is not mergeable"}
                        """, MediaType.APPLICATION_JSON));

        GitHubPullRequestMergeResult result = client.mergePullRequest(12345L, "owner", "repo", 42,
                new GitHubPullRequestMergeRequest("Merge login", "", "squash", "head-sha"));

        assertFalse(result.merged());
        assertEquals("Pull Request is not mergeable", result.message());
        server.verify();
    }

    @Test
    void mapsGitHubErrorsToTheUnifiedUpstreamFailure() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/pulls/42"))
                .andRespond(withBadRequest());

        try {
            client.getPullRequest(12345L, "owner", "repo", 42);
        } catch (qg.qgent.api.ApiException exception) {
            assertEquals("GITHUB_API_UNAVAILABLE", exception.code());
            return;
        }
        throw new AssertionError("Expected GitHub API failure");
    }

    @Test
    void signsAndVerifiesTheInitiatingClientInState() {
        UUID teamId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        String installationUrl = client.createInstallationUrl(teamId, actorId, GitHubClient.MOBILE);
        String state = UriComponentsBuilder.fromUriString(installationUrl).build().getQueryParams().getFirst("state");

        GitHubInstallationState verified = client.verifyInstallationStateDetails(state);
        assertEquals(teamId, verified.teamId());
        assertEquals(GitHubClient.MOBILE, verified.client());
    }

    @Test
    void installationUrlAlwaysUsesNewInstallationPath() {
        UUID teamId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();

        String installationUrl = client.createInstallationUrl(teamId, actorId, GitHubClient.WEB);

        // 路径恒为 /apps/qgents/installations/new，不跳 Configure 或 GitHub settings
        assertEquals("https://github.com/apps/qgents/installations/new",
                UriComponentsBuilder.fromUriString(installationUrl).replaceQuery(null).build().toUriString());
        String state = UriComponentsBuilder.fromUriString(installationUrl).build().getQueryParams().getFirst("state");
        assertFalse(state == null || state.isBlank(), "state 必须存在");

        // state 可还原为当前 team + actor + client
        GitHubInstallationState verified = client.verifyInstallationStateDetails(state);
        assertEquals(teamId, verified.teamId());
        assertEquals(GitHubClient.WEB, verified.client());
    }
}
