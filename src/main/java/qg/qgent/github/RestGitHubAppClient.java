package qg.qgent.github;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongFunction;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.JWTVerifier;
import com.fasterxml.jackson.annotation.JsonProperty;

import qg.qgent.api.ApiException;
import qg.qgent.config.GitHubAppProperties;

public class RestGitHubAppClient implements GitHubAppClient {
    private static final String GITHUB_ACCEPT = "application/vnd.github+json";
    private static final String API_VERSION = "2022-11-28";

    private final RestClient client;
    private final GitHubAppProperties properties;
    private final Clock clock;
    private final LongFunction<String> installationTokenProvider;

    public RestGitHubAppClient(RestClient client, GitHubAppProperties properties, Clock clock) {
        this(client, properties, clock, null);
    }

    /**
     * Test-visible constructor that permits HTTP contract tests to supply a non-secret installation token.
     * Production callers must use the three-argument constructor so tokens continue to be minted from the App key.
     */
    RestGitHubAppClient(RestClient client, GitHubAppProperties properties, Clock clock,
            LongFunction<String> installationTokenProvider) {
        this.client = client;
        this.properties = properties;
        this.clock = clock;
        this.installationTokenProvider = installationTokenProvider == null ? this::installationToken
                : installationTokenProvider;
    }

    @Override
    public String createInstallationUrl(UUID teamId, UUID actorId) {
        requireConfigured();
        String state = JWT.create()
                .withIssuer("qgents-github-installation")
                .withSubject(teamId.toString())
                .withClaim("actorId", actorId.toString())
                .withIssuedAt(Instant.now(clock))
                .withExpiresAt(Instant.now(clock).plusSeconds(600))
                .sign(Algorithm.HMAC256(properties.getStateSecret()));
        return UriComponentsBuilder.fromUriString("https://github.com/apps/{slug}/installations/new")
                .queryParam("state", state)
                .buildAndExpand(properties.getSlug())
                .toUriString();
    }

    @Override
    public UUID verifyInstallationState(String state) {
        requireConfigured();
        try {
            JWTVerifier verifier = JWT.require(Algorithm.HMAC256(properties.getStateSecret()))
                    .withIssuer("qgents-github-installation")
                    .build();
            DecodedJWT token = verifier.verify(state);
            return UUID.fromString(token.getSubject());
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_GITHUB_INSTALLATION_STATE",
                    "GitHub installation state is invalid or expired");
        }
    }

    @Override
    public GitHubInstallationDetails getInstallation(long installationId) {
        requireConfigured();
        try {
            InstallationResponse response = client.get()
                    .uri("/app/installations/{installationId}", installationId)
                    .headers(headers -> githubHeaders(headers, appJwt()))
                    .retrieve()
                    .body(InstallationResponse.class);
            if (response == null || response.account() == null) {
                throw upstreamFailure();
            }
            return new GitHubInstallationDetails(response.id(), response.account().login(), response.account().type());
        } catch (RestClientException exception) {
            throw upstreamFailure();
        }
    }

    @Override
    public List<GitHubRepositoryDetails> listRepositories(long installationId) {
        requireConfigured();
        String token = installationToken(installationId);
        List<GitHubRepositoryDetails> repositories = new ArrayList<>();
        int page = 1;
        try {
            while (true) {
                int currentPage = page;
                RepositoryListResponse response = client.get()
                        .uri(uriBuilder -> uriBuilder.path("/installation/repositories")
                                .queryParam("per_page", 100).queryParam("page", currentPage).build())
                        .headers(headers -> githubHeaders(headers, token))
                        .retrieve()
                        .body(RepositoryListResponse.class);
                if (response == null || response.repositories() == null || response.repositories().isEmpty()) {
                    return repositories;
                }
                response.repositories().forEach(repository -> repositories.add(new GitHubRepositoryDetails(
                        repository.id(), repository.owner().login(), repository.name(), repository.defaultBranch(),
                        repository.visibility(), repository.archived())));
                if (response.repositories().size() < 100) {
                    return repositories;
                }
                page++;
            }
        } catch (RestClientException exception) {
            throw upstreamFailure();
        }
    }

    @Override
    public String createInstallationToken(long installationId) {
        return installationTokenProvider.apply(installationId);
    }

    private String installationToken(long installationId) {
        try {
            InstallationTokenResponse response = client.post()
                    .uri("/app/installations/{installationId}/access_tokens", installationId)
                    .headers(headers -> githubHeaders(headers, appJwt()))
                    .retrieve()
                    .body(InstallationTokenResponse.class);
            if (response == null || response.token() == null || response.token().isBlank()) {
                throw upstreamFailure();
            }
            return response.token();
        } catch (RestClientException exception) {
            throw upstreamFailure();
        }
    }

    private void githubHeaders(HttpHeaders headers, String token) {
        headers.setBearerAuth(token);
        headers.set(HttpHeaders.ACCEPT, GITHUB_ACCEPT);
        headers.set("X-GitHub-Api-Version", API_VERSION);
    }

    private String appJwt() {
        try {
            Instant now = Instant.now(clock);
            return JWT.create()
                    .withIssuer(properties.getAppId())
                    .withIssuedAt(now.minusSeconds(30))
                    .withExpiresAt(now.plusSeconds(540))
                    .sign(Algorithm.RSA256(null, readPrivateKey()));
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_APP_PRIVATE_KEY_UNAVAILABLE",
                    "GitHub App private key is unavailable");
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_APP_PRIVATE_KEY_INVALID",
                    "GitHub App private key cannot be used");
        }
    }

    private RSAPrivateKey readPrivateKey() throws IOException {
        try (Reader reader = Files.newBufferedReader(Path.of(properties.getPrivateKeyPath()));
                PEMParser parser = new PEMParser(reader)) {
            Object parsed = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (parsed instanceof PEMKeyPair pair) {
                return requireRsaPrivateKey(converter.getKeyPair(pair).getPrivate());
            }
            if (parsed instanceof PrivateKeyInfo info) {
                return requireRsaPrivateKey(converter.getPrivateKey(info));
            }
            throw new IllegalArgumentException("Unsupported private key format");
        }
    }

    private RSAPrivateKey requireRsaPrivateKey(java.security.PrivateKey privateKey) {
        if (privateKey instanceof RSAPrivateKey rsaPrivateKey) {
            return rsaPrivateKey;
        }
        throw new IllegalArgumentException("GitHub App private key must be RSA");
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GITHUB_APP_NOT_CONFIGURED",
                    "GitHub App integration is not configured");
        }
    }

    private ApiException upstreamFailure() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_API_UNAVAILABLE",
                "GitHub API is unavailable or rejected the integration credentials");
    }

    private record InstallationResponse(long id, AccountResponse account) { }
    private record AccountResponse(String login, String type) { }
    private record InstallationTokenResponse(String token) { }
    private record RepositoryListResponse(List<RepositoryResponse> repositories) { }
    private record RepositoryResponse(long id, AccountResponse owner, String name,
                                      @JsonProperty("default_branch") String defaultBranch,
                                      String visibility, boolean archived) { }

    @Override
    public GitHubPullRequestDetails createPullRequest(long installationId, String owner, String repo, GitHubPullRequestCreateRequest request) {
        requireConfigured();
        try {
            PullRequestResponse response = client.post()
                    .uri("/repos/{owner}/{repo}/pulls", owner, repo)
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .body(request)
                    .retrieve()
                    .body(PullRequestResponse.class);
            return requirePullRequest(response);
        } catch (RestClientException exception) {
            throw upstreamFailure();
        }
    }

    @Override
    public GitHubPullRequestDetails getPullRequest(long installationId, String owner, String repo, int pullNumber) {
        requireConfigured();
        try {
            PullRequestResponse response = client.get()
                    .uri("/repos/{owner}/{repo}/pulls/{pullNumber}", owner, repo, pullNumber)
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .retrieve()
                    .body(PullRequestResponse.class);
            return requirePullRequest(response);
        } catch (RestClientException exception) {
            throw upstreamFailure();
        }
    }

    @Override
    public List<GitHubCheckRunDetails> getPullRequestChecks(long installationId, String owner, String repo, String headSha) {
        requireConfigured();
        try {
            CheckRunsResponse response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/repos/{owner}/{repo}/commits/{headSha}/check-runs")
                            .queryParam("per_page", 100).build(owner, repo, headSha))
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .retrieve()
                    .body(CheckRunsResponse.class);
            if (response == null || response.checkRuns() == null) {
                throw upstreamFailure();
            }
            return response.checkRuns().stream()
                    .map(check -> new GitHubCheckRunDetails(check.id(), check.name(), check.status(), check.conclusion()))
                    .toList();
        } catch (RestClientException exception) {
            throw upstreamFailure();
        }
    }

    @Override
    public List<GitHubReviewDetails> getPullRequestReviews(long installationId, String owner, String repo, int pullNumber) {
        requireConfigured();
        try {
            ReviewResponse[] response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/repos/{owner}/{repo}/pulls/{pullNumber}/reviews")
                            .queryParam("per_page", 100).build(owner, repo, pullNumber))
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .retrieve()
                    .body(ReviewResponse[].class);
            if (response == null) {
                throw upstreamFailure();
            }
            return java.util.Arrays.stream(response)
                    .map(review -> new GitHubReviewDetails(review.id(), review.state(), review.authorAssociation(),
                            review.user() == null ? null : review.user().login()))
                    .toList();
        } catch (RestClientException exception) {
            throw upstreamFailure();
        }
    }

    @Override
    public GitHubPullRequestMergeResult mergePullRequest(long installationId, String owner, String repo, int pullNumber,
            GitHubPullRequestMergeRequest request) {
        requireConfigured();
        try {
            MergeResponse response = client.put()
                    .uri("/repos/{owner}/{repo}/pulls/{pullNumber}/merge", owner, repo, pullNumber)
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .body(new MergeRequestBody(request.commitTitle(), request.commitMessage(), request.mergeMethod(),
                            request.expectedHeadSha()))
                    .retrieve()
                    .body(MergeResponse.class);
            if (response == null) {
                throw upstreamFailure();
            }
            return new GitHubPullRequestMergeResult(response.merged(), response.sha(), response.message());
        } catch (RestClientException exception) {
            throw upstreamFailure();
        }
    }

    private GitHubPullRequestDetails requirePullRequest(PullRequestResponse response) {
        if (response == null || response.head() == null || response.base() == null || response.head().sha() == null
                || response.head().ref() == null || response.base().ref() == null) {
            throw upstreamFailure();
        }
        return new GitHubPullRequestDetails(response.id(), response.number(), response.state(), response.title(),
                response.head().sha(), response.head().ref(), response.base().ref(), Boolean.TRUE.equals(response.merged()), response.htmlUrl());
    }

    private record PullRequestResponse(long id, int number, String state, String title,
            @JsonProperty("html_url") String htmlUrl, PullRequestRef head, PullRequestRef base, Boolean merged) { }
    private record PullRequestRef(String ref, String sha) { }
    private record CheckRunsResponse(@JsonProperty("check_runs") List<CheckRunResponse> checkRuns) { }
    private record CheckRunResponse(long id, String name, String status, String conclusion) { }
    private record ReviewResponse(long id, String state,
            @JsonProperty("author_association") String authorAssociation, AccountResponse user) { }
    private record MergeRequestBody(@JsonProperty("commit_title") String commitTitle,
            @JsonProperty("commit_message") String commitMessage,
            @JsonProperty("merge_method") String mergeMethod, String sha) { }
    private record MergeResponse(boolean merged, String sha, String message) { }
}
