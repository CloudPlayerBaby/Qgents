package qg.qgent.github;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import qg.qgent.api.ApiException;
import qg.qgent.config.GitHubAppProperties;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongFunction;

public class RestGitHubAppClient implements GitHubAppClient {
    private static final Logger log = LoggerFactory.getLogger(RestGitHubAppClient.class);
    private static final String GITHUB_ACCEPT = "application/vnd.github+json";
    private static final String API_VERSION = "2022-11-28";
    /**
     * 分页拉取仓库列表的总 deadline：显著小于 Webhook RECEIVED 的 5 分钟重领阈值，
     * 避免多页循环累计超时后，重投与原请求并发写业务。
     */
    private static final Duration PAGE_TOTAL_DEADLINE = Duration.ofSeconds(60);

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
        return createInstallationUrl(teamId, actorId, GitHubClient.WEB);
    }

    @Override
    public String createInstallationUrl(UUID teamId, UUID actorId, GitHubClient client) {
        requireConfigured();
        GitHubClient resolvedClient = client == null ? GitHubClient.WEB : client;
        String state = JWT.create()
                .withIssuer("qgents-github-installation")
                .withSubject(teamId.toString())
                .withClaim("actorId", actorId.toString())
                .withClaim("client", resolvedClient.name())
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
        return verifyInstallationStateDetails(state).teamId();
    }

    @Override
    public GitHubInstallationState verifyInstallationStateDetails(String state) {
        requireConfigured();
        try {
            JWTVerifier verifier = JWT.require(Algorithm.HMAC256(properties.getStateSecret()))
                    .withIssuer("qgents-github-installation")
                    .build();
            DecodedJWT token = verifier.verify(state);
            UUID teamId = UUID.fromString(token.getSubject());
            String clientClaim = token.getClaim("client").isNull() ? null : token.getClaim("client").asString();
            GitHubClient client = clientClaim == null || clientClaim.isBlank()
                    ? GitHubClient.WEB
                    : GitHubClient.valueOf(clientClaim);
            return new GitHubInstallationState(teamId, client);
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
        long deadlineNanos = System.nanoTime() + PAGE_TOTAL_DEADLINE.toNanos();
        try {
            while (true) {
                if (System.nanoTime() > deadlineNanos) {
                    throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                            "GITHUB_REPOSITORY_LIST_TIMEOUT",
                            "GitHub 仓库分页拉取超过总超时，请重试");
                }
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
        } catch (RestClientResponseException exception) {
            log.warn("GitHub installation token request rejected: status={}, body={}",
                    exception.getStatusCode().value(), exception.getResponseBodyAsString());
            throw upstreamFailure();
        } catch (RestClientException exception) {
            log.warn("GitHub installation token request failed before receiving a response: {}",
                    exception.getMessage());
            throw upstreamFailure();
        }
    }

    @Override
    public GitHubRepositoryDetails createRepository(long installationId, String accountType, String accountLogin,
                                                    GitHubRepositoryCreateRequest request) {
        requireConfigured();
        try {
            RepositoryResponse response = client.post()
                    .uri(uriBuilder -> "Organization".equalsIgnoreCase(accountType)
                            ? uriBuilder.path("/orgs/{org}/repos").build(accountLogin)
                            : uriBuilder.path("/user/repos").build())
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .body(request)
                    .retrieve()
                    .body(RepositoryResponse.class);
            if (response == null || response.id() == 0 || response.name() == null || response.owner() == null) {
                throw upstreamFailure();
            }
            return new GitHubRepositoryDetails(response.id(), response.owner().login(), response.name(),
                    response.defaultBranch(), response.visibility(), response.archived());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == HttpStatus.UNPROCESSABLE_ENTITY.value()) {
                throw new ApiException(HttpStatus.CONFLICT, "GITHUB_REPOSITORY_CREATE_CONFLICT",
                        "GitHub 仓库创建失败，名称可能已存在或不符合命名规范");
            }
            log.warn("GitHub createRepository rejected: account={} status={} body={}",
                    accountLogin, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            throw upstreamFailure();
        } catch (RestClientException exception) {
            log.warn("GitHub createRepository failed before receiving a response: account={} {}",
                    accountLogin, exception.getMessage());
            throw upstreamFailure();
        }
    }

    @Override
    public void deleteRepository(long installationId, String owner, String repository) {
        requireConfigured();
        try {
            client.delete()
                    .uri("/repos/{owner}/{repository}", owner, repository)
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                return;
            }
            log.warn("GitHub deleteRepository rejected: owner={} repository={} status={}", owner, repository,
                    exception.getStatusCode().value());
            throw upstreamFailure();
        } catch (RestClientException exception) {
            log.warn("GitHub deleteRepository failed before receiving a response: owner={} repository={} {}",
                    owner, repository, exception.getMessage());
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
        } catch (RestClientResponseException exception) {
            log.warn("GitHub installation token request rejected: installationId={}, status={}, body={}",
                    installationId, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            throw upstreamFailure();
        } catch (RestClientException exception) {
            log.warn("GitHub installation token request failed before receiving a response: installationId={}, {}",
                    installationId, exception.getMessage());
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

    private ApiException mergeRejected(RestClientResponseException exception) {
        int status = exception.getStatusCode().value();
        String providerMessage = extractProviderMessage(exception.getResponseBodyAsString());
        String message = providerMessage == null || providerMessage.isBlank()
                ? "GitHub 拒绝了合并请求（HTTP " + status + "）"
                : "GitHub 拒绝了合并请求：" + providerMessage;
        return new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_MERGE_REJECTED", message,
                List.of(java.util.Map.of("providerStatus", status, "providerMessage",
                        providerMessage == null ? "" : providerMessage)));
    }

    private String extractProviderMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\\"message\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
                .matcher(body);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).replace("\\n", " ").replace("\\\"", "\\\"").trim();
    }

    private record InstallationResponse(long id, AccountResponse account) {
    }

    private record AccountResponse(String login, String type) {
    }

    private record InstallationTokenResponse(String token) {
    }

    private record RepositoryListResponse(List<RepositoryResponse> repositories) {
    }

    private record RepositoryResponse(long id, AccountResponse owner, String name,
                                      @JsonProperty("default_branch") String defaultBranch,
                                      String visibility, boolean archived) {
    }

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
        } catch (RestClientResponseException exception) {
            log.warn("GitHub createPullRequest rejected: owner={} repo={} status={} body={}",
                    owner, repo, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            throw upstreamFailure();
        } catch (RestClientException exception) {
            log.warn("GitHub createPullRequest failed before receiving a response: owner={} repo={} {}",
                    owner, repo, exception.getMessage());
            throw upstreamFailure();
        }
    }

    @Override
    public GitHubPullRequestDetails findOpenPullRequest(long installationId, String owner, String repo,
                                                        String sourceBranch, String targetBranch) {
        requireConfigured();
        try {
            List<PullRequestResponse> responses = client.get()
                    .uri(uri -> uri.path("/repos/{owner}/{repo}/pulls")
                            .queryParam("state", "open")
                            .queryParam("head", owner + ":" + sourceBranch)
                            .queryParam("base", targetBranch)
                            .queryParam("per_page", 1)
                            .build(owner, repo))
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<List<PullRequestResponse>>() {
                    });
            return responses == null || responses.isEmpty() ? null : requirePullRequest(responses.get(0));
        } catch (RestClientResponseException exception) {
            log.warn("GitHub findOpenPullRequest rejected: owner={} repo={} status={} body={}",
                    owner, repo, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            throw upstreamFailure();
        } catch (RestClientException exception) {
            log.warn("GitHub findOpenPullRequest failed before receiving a response: owner={} repo={} {}",
                    owner, repo, exception.getMessage());
            throw upstreamFailure();
        }
    }

    @Override
    public GitHubBranchDetails getBranch(long installationId, String owner, String repo, String branch) {
        requireConfigured();
        try {
            BranchResponse response = client.get()
                    .uri("/repos/{owner}/{repo}/branches/{branch}", owner, repo, branch)
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .retrieve()
                    .body(BranchResponse.class);
            if (response == null || response.commit() == null || response.commit().sha() == null) {
                throw upstreamFailure();
            }
            return new GitHubBranchDetails(response.name(), response.commit().sha());
        } catch (RestClientResponseException exception) {
            // 记录状态码与响应体：空仓库/分支不存在时 GitHub 返回 404，需与凭据失败（401）区分定位
            log.warn("GitHub getBranch rejected: owner={} repo={} branch={} status={} body={}",
                    owner, repo, branch, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            if (exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "GIT_BRANCH_NOT_FOUND", "GitHub 远程分支不存在");
            }
            throw upstreamFailure();
        } catch (RestClientException exception) {
            log.warn("GitHub getBranch failed before receiving a response: owner={} repo={} branch={} {}",
                    owner, repo, branch, exception.getMessage());
            throw upstreamFailure();
        }
    }

    @Override
    public List<GitHubBranchDetails> listBranches(long installationId, String owner, String repo) {
        requireConfigured();
        List<GitHubBranchDetails> branches = new ArrayList<>();
        int page = 1;
        long deadlineNanos = System.nanoTime() + PAGE_TOTAL_DEADLINE.toNanos();
        try {
            while (true) {
                if (System.nanoTime() > deadlineNanos) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_API_UNAVAILABLE",
                            "GitHub 分支列表请求超过总超时，请重试");
                }
                int currentPage = page;
                List<BranchResponse> response = client.get()
                        .uri(uriBuilder -> uriBuilder.path("/repos/{owner}/{repo}/branches")
                                .queryParam("per_page", 100).queryParam("page", currentPage).build(owner, repo))
                        .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                        .retrieve()
                        .body(new org.springframework.core.ParameterizedTypeReference<List<BranchResponse>>() {
                        });
                if (response == null || response.isEmpty()) {
                    return branches;
                }
                for (BranchResponse branch : response) {
                    if (branch == null || branch.name() == null || branch.commit() == null
                            || branch.commit().sha() == null) {
                        throw upstreamFailure();
                    }
                    branches.add(new GitHubBranchDetails(branch.name(), branch.commit().sha()));
                }
                if (response.size() < 100) {
                    return branches;
                }
                page++;
            }
        } catch (RestClientResponseException exception) {
            log.warn("GitHub listBranches rejected: owner={} repo={} status={} body={}",
                    owner, repo, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            if (exception.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "GITHUB_BRANCH_READ_FORBIDDEN",
                        "GitHub App 没有读取该仓库分支的权限");
            }
            throw upstreamFailure();
        } catch (RestClientException exception) {
            log.warn("GitHub listBranches failed before receiving a response: owner={} repo={} {}",
                    owner, repo, exception.getMessage());
            throw upstreamFailure();
        }
    }

    @Override
    public GitHubBranchDetails createBranch(long installationId, String owner, String repo,
                                            String branchName, String sourceSha) {
        requireConfigured();
        try {
            RefResponse response = client.post()
                    .uri("/repos/{owner}/{repo}/git/refs", owner, repo)
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .body(new CreateRefRequest("refs/heads/" + branchName, sourceSha))
                    .retrieve()
                    .body(RefResponse.class);
            String expectedRef = "refs/heads/" + branchName;
            if (response == null || !expectedRef.equals(response.ref()) || response.object() == null
                    || response.object().sha() == null) {
                throw upstreamFailure();
            }
            return new GitHubBranchDetails(branchName, response.object().sha());
        } catch (RestClientResponseException exception) {
            log.warn("GitHub createBranch rejected: owner={} repo={} branch={} status={} body={}",
                    owner, repo, branchName, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            if (exception.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "GITHUB_BRANCH_CREATE_FORBIDDEN",
                        "GitHub App 没有创建该仓库分支的权限");
            }
            if (exception.getStatusCode().value() == HttpStatus.UNPROCESSABLE_ENTITY.value()
                    || exception.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                throw new ApiException(HttpStatus.CONFLICT, "GIT_BRANCH_ALREADY_EXISTS",
                        "GitHub 远程分支已存在或无法创建");
            }
            throw upstreamFailure();
        } catch (RestClientException exception) {
            log.warn("GitHub createBranch failed before receiving a response: owner={} repo={} branch={} {}",
                    owner, repo, branchName, exception.getMessage());
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
    public GitHubPullRequestCommitList getPullRequestCommits(long installationId, String owner, String repo,
                                                              int pullNumber, int limit) {
        requireConfigured();
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        try {
            PullRequestResponse pullRequest = client.get()
                    .uri("/repos/{owner}/{repo}/pulls/{pullNumber}", owner, repo, pullNumber)
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .retrieve()
                    .body(PullRequestResponse.class);
            if (pullRequest == null || pullRequest.commits() == null || pullRequest.commits() < 0) {
                throw upstreamFailure();
            }
            PullRequestCommitResponse[] response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/repos/{owner}/{repo}/pulls/{pullNumber}/commits")
                            .queryParam("per_page", limit).build(owner, repo, pullNumber))
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .retrieve()
                    .body(PullRequestCommitResponse[].class);
            if (response == null) {
                throw upstreamFailure();
            }
            List<GitHubPullRequestCommitDetails> items = java.util.Arrays.stream(response)
                    .map(this::toPullRequestCommit)
                    .toList();
            return new GitHubPullRequestCommitList(Math.max(pullRequest.commits(), items.size()), items);
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
    public GitHubPullRequestCommentDetails createPullRequestComment(long installationId, String owner, String repo,
                                                                      int pullNumber, GitHubPullRequestCommentRequest request) {
        requireConfigured();
        try {
            CommentResponse response = client.post()
                    .uri("/repos/{owner}/{repo}/issues/{pullNumber}/comments",
                            owner, repo, pullNumber)
                    .headers(headers -> githubHeaders(headers, installationTokenProvider.apply(installationId)))
                    .body(request)
                    .retrieve()
                    .body(CommentResponse.class);
            if (response == null || response.id() == 0 || response.body() == null) {
                throw upstreamFailure();
            }
            return new GitHubPullRequestCommentDetails(response.id(), response.body(), response.htmlUrl(), response.createdAt());
        } catch (RestClientResponseException exception) {
            log.warn("GitHub createPullRequestComment rejected: owner={} repo={} number={} status={} body={}",
                    owner, repo, pullNumber, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            throw upstreamFailure();
        } catch (RestClientException exception) {
            log.warn("GitHub createPullRequestComment failed: owner={} repo={} number={} {}",
                    owner, repo, pullNumber, exception.getMessage());
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
        } catch (RestClientResponseException exception) {
            log.warn("GitHub mergePullRequest rejected: owner={} repo={} number={} status={} body={}",
                    owner, repo, pullNumber, exception.getStatusCode().value(), exception.getResponseBodyAsString());
            throw mergeRejected(exception);
        } catch (RestClientException exception) {
            log.warn("GitHub mergePullRequest failed before receiving a response: owner={} repo={} number={} {}",
                    owner, repo, pullNumber, exception.getMessage());
            throw upstreamFailure();
        }
    }

    private GitHubPullRequestDetails requirePullRequest(PullRequestResponse response) {
        if (response == null || response.head() == null || response.base() == null || response.head().sha() == null
                || response.head().ref() == null || response.base().ref() == null) {
            throw upstreamFailure();
        }
        return new GitHubPullRequestDetails(response.id(), response.number(), response.state(), response.title(),
                response.head().sha(), response.head().ref(), response.base().ref(), Boolean.TRUE.equals(response.merged()),
                response.htmlUrl(), response.mergeable(), response.mergeableState(), response.base().sha());
    }

    private GitHubPullRequestCommitDetails toPullRequestCommit(PullRequestCommitResponse response) {
        if (response == null || response.sha() == null || response.sha().isBlank() || response.commit() == null
                || response.commit().message() == null || response.commit().message().isBlank()
                || response.commit().author() == null || response.commit().author().name() == null
                || response.commit().author().name().isBlank() || response.commit().author().date() == null
                || response.commit().author().date().isBlank()) {
            throw upstreamFailure();
        }
        return new GitHubPullRequestCommitDetails(response.sha(), response.commit().message(),
                response.commit().author().name(), null, response.commit().author().date());
    }

    private record PullRequestResponse(long id, int number, String state, String title,
                                       @JsonProperty("html_url") String htmlUrl, PullRequestRef head,
                                       PullRequestRef base, Boolean merged, Boolean mergeable,
                                       @JsonProperty("mergeable_state") String mergeableState, Integer commits) {
    }

    private record PullRequestRef(String ref, String sha) {
    }

    private record CheckRunsResponse(@JsonProperty("check_runs") List<CheckRunResponse> checkRuns) {
    }

    private record CheckRunResponse(long id, String name, String status, String conclusion) {
    }

    private record ReviewResponse(long id, String state,
                                  @JsonProperty("author_association") String authorAssociation, AccountResponse user) {
    }

    private record PullRequestCommitResponse(String sha, PullRequestCommitMetadata commit) {
    }

    private record PullRequestCommitMetadata(String message, GitCommitAuthor author) {
    }

    private record GitCommitAuthor(String name, String date) {
    }


    private record CommentResponse(long id, String body,
                                   @JsonProperty("html_url") String htmlUrl,
                                   @JsonProperty("created_at") String createdAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record MergeRequestBody(@JsonProperty("commit_title") String commitTitle,
                                    @JsonProperty("commit_message") String commitMessage,
                                    @JsonProperty("merge_method") String mergeMethod, String sha) {
    }

    private record MergeResponse(boolean merged, String sha, String message) {
    }

    private record BranchResponse(String name, CommitResponse commit) {
    }

    private record CommitResponse(String sha) {
    }

    private record CreateRefRequest(String ref, String sha) {
    }

    private record RefResponse(String ref, RefObject object) {
    }

    private record RefObject(String sha) {
    }
}
