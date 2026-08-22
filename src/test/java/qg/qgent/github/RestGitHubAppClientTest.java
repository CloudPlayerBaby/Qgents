package qg.qgent.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
    void createsRepositoryUnderOrganizationWithAutoInit() {
        GitHubRepositoryCreateRequest request = new GitHubRepositoryCreateRequest("new-repo", "desc", true, true);
        server.expect(once(), requestTo("https://api.github.com/orgs/qgents-org/repos"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        {"id":9001,"owner":{"login":"qgents-org"},"name":"new-repo",
                         "default_branch":"main","visibility":"private","archived":false}
                        """, MediaType.APPLICATION_JSON));

        GitHubRepositoryDetails details = client.createRepository(12345L, "Organization", "qgents-org", request);
        assertEquals("new-repo", details.getName());
        assertEquals("qgents-org", details.getOwnerLogin());
        assertEquals("main", details.getDefaultBranch());
        server.verify();
    }

    @Test
    void createsRepositoryUnderUserAccount() {
        GitHubRepositoryCreateRequest request = new GitHubRepositoryCreateRequest("new-repo", null, false, false);
        server.expect(once(), requestTo("https://api.github.com/user/repos"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":9002,"owner":{"login":"user-login"},"name":"new-repo",
                         "default_branch":"main","visibility":"public","archived":false}
                        """, MediaType.APPLICATION_JSON));

        GitHubRepositoryDetails details = client.createRepository(12345L, "User", "user-login", request);
        assertEquals("user-login", details.getOwnerLogin());
        assertEquals("new-repo", details.getName());
        server.verify();
    }

    @Test
    void mapsRepositoryCreateConflictToConflictCode() {
        GitHubRepositoryCreateRequest request = new GitHubRepositoryCreateRequest("taken", null, true, true);
        server.expect(once(), requestTo("https://api.github.com/orgs/qgents-org/repos"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body("{\"message\":\"name already exists\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        try {
            client.createRepository(12345L, "Organization", "qgents-org", request);
        } catch (qg.qgent.api.ApiException exception) {
            assertEquals("GITHUB_REPOSITORY_CREATE_CONFLICT", exception.code());
            return;
        }
        throw new AssertionError("Expected repository create conflict");
    }

    @Test
    void deletesCompensationRepositoryWithInstallationToken() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/created-repo"))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withStatus(HttpStatus.NO_CONTENT));

        client.deleteRepository(12345L, "owner", "created-repo");

        server.verify();
    }

    @Test
    void getsPullRequest() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/pulls/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":12345,"number":42,"state":"open","title":"Test PR",
                         "html_url":"https://github.com/owner/repo/pull/42",
                         "mergeable":false,"mergeable_state":"dirty",
                         "head":{"ref":"feat/mock","sha":"head-sha"},"base":{"ref":"main","sha":"base-sha"}}
                        """, MediaType.APPLICATION_JSON));
        GitHubPullRequestDetails details = client.getPullRequest(12345L, "owner", "repo", 42);
        assertEquals(42, details.number());
        assertEquals("open", details.state());
        assertEquals(Boolean.FALSE, details.mergeable());
        assertEquals("dirty", details.mergeableState());
        assertEquals("base-sha", details.baseSha());
        server.verify();
    }

    @Test
    void mergabilityIsNullWhenGitHubHasNotComputedIt() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/pulls/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":12345,"number":42,"state":"open","title":"Test PR",
                         "html_url":"https://github.com/owner/repo/pull/42",
                         "mergeable":null,"mergeable_state":"unknown",
                         "head":{"ref":"feat/mock","sha":"head-sha"},"base":{"ref":"main","sha":"base-sha"}}
                        """, MediaType.APPLICATION_JSON));
        GitHubPullRequestDetails details = client.getPullRequest(12345L, "owner", "repo", 42);
        assertEquals(null, details.mergeable());
        assertEquals("unknown", details.mergeableState());
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
    void getsPullRequestCommitsWithRealTotalCount() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/pulls/42"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"id":1,"number":42,"commits":5}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/pulls/42/commits?per_page=3"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [{"sha":"abc123","commit":{"message":"实现提交记录\\n\\n更多说明",
                        "author":{"name":"Alice","date":"2026-08-22T00:00:00Z"}},
                        "author":{"id":123,"login":"alice"}}]
                        """, MediaType.APPLICATION_JSON));

        GitHubPullRequestCommitList commits = client.getPullRequestCommits(12345L, "owner", "repo", 42, 3);

        assertEquals(5, commits.totalCount());
        assertEquals(1, commits.items().size());
        assertEquals("abc123", commits.items().getFirst().sha());
        assertEquals("Alice", commits.items().getFirst().authorName());
        assertEquals(null, commits.items().getFirst().authorUserId());
        server.verify();
    }

    @Test
    void createsPullRequestIssueComment() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/issues/42/comments"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        {"id":3001,"body":"Please add a regression test",
                         "html_url":"https://github.com/owner/repo/pull/42#issuecomment-3001",
                         "created_at":"2026-08-18T15:00:00Z"}
                        """, MediaType.APPLICATION_JSON));

        GitHubPullRequestCommentDetails comment = client.createPullRequestComment(12345L, "owner", "repo", 42,
                new GitHubPullRequestCommentRequest("Please add a regression test"));
        assertEquals(3001L, comment.id());
        assertEquals("Please add a regression test", comment.body());
        assertEquals("https://github.com/owner/repo/pull/42#issuecomment-3001", comment.htmlUrl());
        server.verify();
    }

    @Test
    void returnsGitHubMergeOutcomeInsteadOfAssumingSuccess() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/pulls/42/merge"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"commit_title\":\"Merge login\",\"merge_method\":\"squash\",\"sha\":\"head-sha\"}"))
                .andRespond(withSuccess("""
                        {"sha":"merge-sha","merged":false,"message":"Pull Request is not mergeable"}
                        """, MediaType.APPLICATION_JSON));

        GitHubPullRequestMergeResult result = client.mergePullRequest(12345L, "owner", "repo", 42,
                new GitHubPullRequestMergeRequest("Merge login", null, "squash", "head-sha"));

        assertFalse(result.merged());
        assertEquals("Pull Request is not mergeable", result.message());
        server.verify();
    }

    @Test
    void preservesRejectedGitHubMergeReason() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/pulls/42/merge"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("{\"message\":\"Resource not accessible by integration\"}"));

        try {
            client.mergePullRequest(12345L, "owner", "repo", 42,
                    new GitHubPullRequestMergeRequest("Merge login", "", "squash", "head-sha"));
        } catch (qg.qgent.api.ApiException exception) {
            assertEquals("GITHUB_MERGE_REJECTED", exception.code());
            assertTrue(exception.getMessage().contains("Resource not accessible by integration"));
            server.verify();
            return;
        }
        throw new AssertionError("Expected GitHub merge failure");
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
    void listsRemoteBranchesWithPaginationContract() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/branches?per_page=100&page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("""
                        [{"name":"develop","commit":{"sha":"develop-sha"}},
                         {"name":"main","commit":{"sha":"main-sha"}}]
                        """, MediaType.APPLICATION_JSON));

        List<GitHubBranchDetails> branches = client.listBranches(12345L, "owner", "repo");

        assertEquals(2, branches.size());
        assertEquals("develop", branches.get(0).name());
        assertEquals("main-sha", branches.get(1).commitSha());
        server.verify();
    }

    @Test
    void createsRemoteBranchFromSourceSha() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/git/refs"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(content().json("{\"ref\":\"refs/heads/develop\",\"sha\":\"source-sha\"}"))
                .andRespond(withSuccess("""
                        {"ref":"refs/heads/develop","object":{"sha":"source-sha","type":"commit"}}
                        """, MediaType.APPLICATION_JSON));

        GitHubBranchDetails branch = client.createBranch(12345L, "owner", "repo", "develop", "source-sha");

        assertEquals("develop", branch.name());
        assertEquals("source-sha", branch.commitSha());
        server.verify();
    }

    @Test
    void mapsMissingBranchToStableNotFoundCode() {
        server.expect(once(), requestTo("https://api.github.com/repos/owner/repo/branches/missing"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        try {
            client.getBranch(12345L, "owner", "repo", "missing");
        } catch (qg.qgent.api.ApiException exception) {
            assertEquals("GIT_BRANCH_NOT_FOUND", exception.code());
            assertEquals(HttpStatus.NOT_FOUND, exception.status());
            return;
        }
        throw new AssertionError("Expected missing branch failure");
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
