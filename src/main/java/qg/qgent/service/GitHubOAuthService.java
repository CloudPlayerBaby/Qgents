package qg.qgent.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import qg.qgent.api.ApiException;
import qg.qgent.auth.UuidV7;
import qg.qgent.config.GitHubOAuthProperties;
import qg.qgent.dto.GitHubOAuthStartResponse;
import qg.qgent.dto.GitHubOAuthStatusResponse;
import qg.qgent.entity.GitHubInstallationEntity;
import qg.qgent.entity.GitHubOAuthStateEntity;
import qg.qgent.entity.GitHubUserAuthorizationEntity;
import qg.qgent.entity.TeamMemberEntity;
import qg.qgent.github.GitHubOAuthClient;
import qg.qgent.mapper.GitHubInstallationMapper;
import qg.qgent.mapper.GitHubOAuthStateMapper;
import qg.qgent.mapper.GitHubUserAuthorizationMapper;
import qg.qgent.mapper.TeamMemberMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** GitHub OAuth 用户授权业务；不提供 GitHub 登录，不改变 Qgents 自有账号体系。 */
@Service
public class GitHubOAuthService {
    private static final String PROVIDER = "GITHUB";
    private final GitHubOAuthProperties properties;
    private final GitHubOAuthClient client;
    private final GitHubOAuthStateMapper stateMapper;
    private final GitHubUserAuthorizationMapper authorizationMapper;
    private final TeamMemberMapper teamMemberMapper;
    private final GitHubInstallationMapper installationMapper;
    private final GitHubOAuthTokenCipher cipher;
    private final Clock clock;

    public GitHubOAuthService(GitHubOAuthProperties properties, GitHubOAuthClient client,
                              GitHubOAuthStateMapper stateMapper,
                              GitHubUserAuthorizationMapper authorizationMapper,
                              TeamMemberMapper teamMemberMapper, GitHubInstallationMapper installationMapper,
                              GitHubOAuthTokenCipher cipher, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.stateMapper = stateMapper;
        this.authorizationMapper = authorizationMapper;
        this.teamMemberMapper = teamMemberMapper;
        this.installationMapper = installationMapper;
        this.cipher = cipher;
        this.clock = clock;
    }

    public GitHubOAuthStartResponse start(UUID userId, String clientType) {
        requireConfigured();
        String normalizedClient = normalizeClient(clientType);
        LocalDateTime now = now();
        LocalDateTime expires = now.plusSeconds(Math.max(60, properties.getStateTtlSeconds()));
        UUID stateId = UuidV7.next();
        String state = JWT.create().withIssuer("qgents-github-oauth")
                .withJWTId(stateId.toString()).withSubject(userId.toString())
                .withClaim("client", normalizedClient).withIssuedAt(now.toInstant(ZoneOffset.UTC))
                .withExpiresAt(expires.toInstant(ZoneOffset.UTC))
                .sign(Algorithm.HMAC256(properties.getStateSecret()));
        GitHubOAuthStateEntity entity = new GitHubOAuthStateEntity();
        entity.setId(stateId);
        entity.setStateHash(hash(state));
        entity.setUserId(userId);
        entity.setClient(normalizedClient);
        entity.setExpiresAt(expires);
        entity.setCreatedAt(now);
        stateMapper.insert(entity);
        return new GitHubOAuthStartResponse(client.buildAuthorizationUrl(state, properties.scopeList()),
                OffsetDateTime.of(expires, ZoneOffset.UTC));
    }

    /**
     * 处理 OAuth callback。state 在外部 GitHub 调用前一次性消费，失败时用户重新发起授权即可。
     */
    public CallbackResult callback(String code, String state, String error) {
        StateContext context = consumeState(state);
        try {
            if (error != null && !error.isBlank()) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GITHUB_OAUTH_CALLBACK_DENIED",
                        "用户拒绝了 GitHub 授权");
            }
            if (code == null || code.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "GITHUB_OAUTH_CALLBACK_FAILED",
                        "GitHub OAuth 回调缺少 code");
            }
            GitHubOAuthClient.OAuthToken token = client.exchangeCode(code);
            GitHubOAuthClient.GitHubUser githubUser = client.getCurrentUser(token.accessToken());
            saveAuthorization(context.userId(), githubUser, token);
            return new CallbackResult(context.client());
        } catch (ApiException exception) {
            // state 已校验并绑定了客户端类型；不能因为后续 GitHub 调用失败而固定回跳 WEB。
            throw new CallbackApiException(context.client(), exception);
        } catch (RuntimeException exception) {
            // 不把数据库、JSON 或其他内部异常暴露到浏览器回跳参数。
            throw new CallbackApiException(context.client(), new ApiException(HttpStatus.BAD_GATEWAY,
                    "GITHUB_OAUTH_CALLBACK_FAILED", "GitHub OAuth 回调处理失败"));
        }
    }

    /**
     * 仅用于 callback 错误回跳选择端；即使 state 无效也只会返回固定的 WEB/MOBILE 枚举，
     * 不参与用户、授权或权限判断。
     */
    public String callbackClientHint(String rawState) {
        try {
            DecodedJWT decoded = JWT.decode(rawState);
            String value = decoded.getClaim("client").asString();
            return "MOBILE".equalsIgnoreCase(value) ? "MOBILE" : "WEB";
        } catch (RuntimeException ignored) {
            return "WEB";
        }
    }

    public GitHubOAuthStatusResponse status(UUID userId) {
        GitHubUserAuthorizationEntity entity = findByUser(userId);
        GitHubOAuthStatusResponse response = new GitHubOAuthStatusResponse();
        String oauthLogin = null;
        if (entity != null && "ACTIVE".equals(entity.getStatus())) {
            response.setAuthorized(true);
            response.setProvider(PROVIDER);
            response.setGithubUserId(entity.getProviderUserId());
            response.setGithubLogin(entity.getProviderLogin());
            response.setScopes(parseScopes(entity.getScopes()));
            response.setAuthorizedAt(toOffset(entity.getAuthorizedAt()));
            response.setLastValidatedAt(toOffset(entity.getLastValidatedAt()));
            List<String> scopes = response.getScopes();
            // repo 覆盖私有与公开；public_repo 只覆盖公开。默认建仓为私有，因此私有能力需要 repo。
            response.setCanCreatePublicPersonalRepository(hasScope(scopes, "repo")
                    || hasScope(scopes, "public_repo"));
            response.setCanCreatePrivatePersonalRepository(hasScope(scopes, "repo"));
            oauthLogin = entity.getProviderLogin();
        }
        applyPersonalRepositorySetup(response, userId, oauthLogin);
        return response;
    }

    /**
     * 计算个人仓库开通前置状态，供前端在 OAuth 绑定前后做引导：
     * NOT_OWNER（非团队 Owner，无法个人建仓）、NEED_INSTALLATION（需先安装 GitHub App）、
     * NEED_OAUTH（App 已装但未绑 OAuth）、ACCOUNT_MISMATCH（OAuth 账号与 App 安装账号不一致，
     * 应提示用户重新绑定 OAuth 而非重装 App）、READY（可建仓）。
     * 安装账号取用户作为 Owner 的团队下 ACTIVE 的 USER 类型安装。
     */
    private void applyPersonalRepositorySetup(GitHubOAuthStatusResponse response, UUID userId, String oauthLogin) {
        List<UUID> ownedTeamIds = teamMemberMapper.selectByUserId(userId).stream()
                .filter(member -> "TEAM_OWNER".equals(member.getRole()))
                .map(TeamMemberEntity::getTeamId).distinct().toList();
        if (ownedTeamIds.isEmpty()) {
            response.setPersonalRepositorySetup("NOT_OWNER");
            return;
        }
        List<String> userInstallLogins = installationMapper.selectList(Wrappers.<GitHubInstallationEntity>lambdaQuery()
                        .in(GitHubInstallationEntity::getTeamId, ownedTeamIds)
                        .eq(GitHubInstallationEntity::getAccountType, "USER")
                        .eq(GitHubInstallationEntity::getStatus, "ACTIVE"))
                .stream().map(GitHubInstallationEntity::getAccountLogin)
                .filter(Objects::nonNull).distinct().toList();
        if (userInstallLogins.isEmpty()) {
            response.setPersonalRepositorySetup("NEED_INSTALLATION");
            return;
        }
        if (oauthLogin == null || oauthLogin.isBlank()) {
            response.setPersonalRepositorySetup("NEED_OAUTH");
            return;
        }
        if (userInstallLogins.stream().anyMatch(login -> login.equalsIgnoreCase(oauthLogin))) {
            response.setPersonalRepositorySetup("READY");
            return;
        }
        response.setPersonalRepositorySetup("ACCOUNT_MISMATCH");
        response.setExpectedInstallationLogin(userInstallLogins.get(0));
    }

    /**
     * 撤销过程不持有数据库事务调用 GitHub。远程失败时置为 ERROR 并保留加密密文，
     * 后续相同操作可以重试；任何 ERROR/REVOKED 状态都不会再被个人建仓使用。
     */
    public void revoke(UUID userId) {
        GitHubUserAuthorizationEntity entity = findByUser(userId);
        if (entity == null || !("ACTIVE".equals(entity.getStatus()) || "ERROR".equals(entity.getStatus()))) return;
        String encryptedToken = entity.getAccessTokenCiphertext();
        if (encryptedToken == null || encryptedToken.isBlank()) {
            authorizationMapper.markRevoked(entity.getId(), now());
            return;
        }
        // 先原子抢占 REVOKING：撤销一旦开始，并发建仓因授权非 ACTIVE 会被 requirePersonalCredential 拒绝。
        if (authorizationMapper.claimRevoking(userId, now()) != 1) return;
        try {
            client.revokeAccessToken(cipher.decrypt(encryptedToken));
        } catch (ApiException exception) {
            authorizationMapper.markRevokeFailed(entity.getId(), exception.code(), now());
            throw exception;
        } catch (RuntimeException exception) {
            authorizationMapper.markRevokeFailed(entity.getId(), "GITHUB_OAUTH_UPSTREAM_UNAVAILABLE", now());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GITHUB_OAUTH_UPSTREAM_UNAVAILABLE",
                    "GitHub OAuth 撤销授权服务暂不可用");
        }
        authorizationMapper.markRevoked(entity.getId(), now());
    }

    /**
     * GitHub 远端 401 时回写本地授权失效（EXPIRED 并清除密文），使状态接口返回未授权。
     * 仅作用于当前仍为 ACTIVE 的记录；已撤销或已重新授权的记录不受影响。
     */
    public void markInvalid(UUID userId, String code) {
        authorizationMapper.markInvalid(userId, code, now());
    }

    public PersonalCredential requirePersonalCredential(UUID userId) {
        GitHubUserAuthorizationEntity entity = findByUser(userId);
        if (entity == null) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_OAUTH_REQUIRED",
                    "创建个人 GitHub 仓库前请先关联个人 GitHub 账号");
        }
        if (!"ACTIVE".equals(entity.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_OAUTH_REVOKED",
                    "个人 GitHub 授权已失效，请重新关联 GitHub 账号");
        }
        return new PersonalCredential(cipher.decrypt(entity.getAccessTokenCiphertext()),
                entity.getProviderUserId(), entity.getProviderLogin(), parseScopes(entity.getScopes()));
    }

    private void saveAuthorization(UUID userId, GitHubOAuthClient.GitHubUser githubUser,
                                   GitHubOAuthClient.OAuthToken token) {
        GitHubUserAuthorizationEntity byProvider = findByProvider(githubUser.id());
        if (byProvider != null && !userId.equals(byProvider.getUserId())) {
            throw new ApiException(HttpStatus.CONFLICT, "GITHUB_OAUTH_ACCOUNT_MISMATCH",
                    "该 GitHub 账号已绑定其他 Qgents 用户");
        }
        LocalDateTime now = now();
        GitHubUserAuthorizationEntity entity = byProvider != null ? byProvider : findByUser(userId);
        boolean insert = entity == null;
        if (insert) {
            entity = new GitHubUserAuthorizationEntity();
            entity.setId(UuidV7.next());
            entity.setUserId(userId);
            entity.setProvider(PROVIDER);
            entity.setCreatedAt(now);
        }
        applyAuthorization(entity, githubUser, token, now);
        try {
            if (insert) authorizationMapper.insert(entity);
            else authorizationMapper.updateById(entity);
        } catch (DuplicateKeyException duplicate) {
            // 并发回调刚写入同 user 或同 provider_user 的记录，重读并合并；仍冲突则返回明确错误码。
            GitHubUserAuthorizationEntity existing = findByProvider(githubUser.id());
            if (existing != null && !userId.equals(existing.getUserId())) {
                throw new ApiException(HttpStatus.CONFLICT, "GITHUB_OAUTH_ACCOUNT_MISMATCH",
                        "该 GitHub 账号已绑定其他 Qgents 用户");
            }
            existing = findByUser(userId);
            if (existing == null) {
                throw new ApiException(HttpStatus.CONFLICT, "GITHUB_OAUTH_CALLBACK_CONFLICT",
                        "GitHub OAuth 授权状态冲突，请重试");
            }
            if (existing.getProviderUserId() != null && !existing.getProviderUserId().equals(githubUser.id())) {
                throw new ApiException(HttpStatus.CONFLICT, "GITHUB_OAUTH_ACCOUNT_MISMATCH",
                        "该 Qgents 用户已绑定其他 GitHub 账号");
            }
            applyAuthorization(existing, githubUser, token, now);
            authorizationMapper.updateById(existing);
        }
    }

    private void applyAuthorization(GitHubUserAuthorizationEntity entity,
                                    GitHubOAuthClient.GitHubUser githubUser,
                                    GitHubOAuthClient.OAuthToken token, LocalDateTime now) {
        entity.setProviderUserId(githubUser.id());
        entity.setProviderLogin(githubUser.login());
        entity.setAccessTokenCiphertext(cipher.encrypt(token.accessToken()));
        entity.setScopes(String.join(",", token.scopes() == null ? List.of() : token.scopes()));
        entity.setStatus("ACTIVE");
        entity.setLastErrorCode(null);
        entity.setAuthorizedAt(entity.getAuthorizedAt() == null ? now : entity.getAuthorizedAt());
        entity.setLastValidatedAt(now);
        entity.setRevokedAt(null);
        entity.setUpdatedAt(now);
    }

    private GitHubUserAuthorizationEntity findByProvider(long providerUserId) {
        return authorizationMapper.selectOne(Wrappers.<GitHubUserAuthorizationEntity>lambdaQuery()
                .eq(GitHubUserAuthorizationEntity::getProvider, PROVIDER)
                .eq(GitHubUserAuthorizationEntity::getProviderUserId, providerUserId));
    }

    private StateContext consumeState(String rawState) {
        requireConfigured();
        if (rawState == null || rawState.isBlank()) throw invalidState();
        DecodedJWT decoded;
        try {
            JWTVerifier verifier = JWT.require(Algorithm.HMAC256(properties.getStateSecret()))
                    .withIssuer("qgents-github-oauth").build();
            decoded = verifier.verify(rawState);
        } catch (TokenExpiredException e) {
            throw expiredState();
        } catch (RuntimeException e) {
            throw invalidState();
        }
        UUID stateId;
        UUID userId;
        String clientType;
        try {
            stateId = UUID.fromString(decoded.getId());
            userId = UUID.fromString(decoded.getSubject());
            clientType = normalizeClient(decoded.getClaim("client").asString());
        } catch (RuntimeException e) {
            throw invalidState();
        }
        GitHubOAuthStateEntity entity = stateMapper.selectOne(Wrappers.<GitHubOAuthStateEntity>lambdaQuery()
                .eq(GitHubOAuthStateEntity::getId, stateId)
                .eq(GitHubOAuthStateEntity::getStateHash, hash(rawState)));
        if (entity == null || !userId.equals(entity.getUserId()) || !clientType.equals(entity.getClient())) {
            throw invalidState();
        }
        LocalDateTime current = now();
        if (entity.getExpiresAt() == null || !entity.getExpiresAt().isAfter(current)) throw expiredState();
        if (entity.getConsumedAt() != null) throw replayedState();
        if (stateMapper.consume(stateId, current) != 1) {
            GitHubOAuthStateEntity latest = stateMapper.selectById(stateId);
            if (latest != null && latest.getConsumedAt() != null) throw replayedState();
            if (latest != null && latest.getExpiresAt() != null && !latest.getExpiresAt().isAfter(current)) {
                throw expiredState();
            }
            throw invalidState();
        }
        return new StateContext(userId, clientType);
    }

    private GitHubUserAuthorizationEntity findByUser(UUID userId) {
        return authorizationMapper.selectOne(Wrappers.<GitHubUserAuthorizationEntity>lambdaQuery()
                .eq(GitHubUserAuthorizationEntity::getUserId, userId)
                .eq(GitHubUserAuthorizationEntity::getProvider, PROVIDER));
    }
    private ApiException invalidState() { return new ApiException(HttpStatus.BAD_REQUEST,
            "GITHUB_OAUTH_STATE_INVALID", "GitHub OAuth state 无效或已过期"); }
    private ApiException expiredState() { return new ApiException(HttpStatus.BAD_REQUEST,
            "GITHUB_OAUTH_STATE_EXPIRED", "GitHub OAuth state 已过期"); }
    private ApiException replayedState() { return new ApiException(HttpStatus.CONFLICT,
            "GITHUB_OAUTH_STATE_REPLAYED", "GitHub OAuth state 已被使用"); }
    private void requireConfigured() { if (!properties.configured()) throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
            "GITHUB_OAUTH_NOT_CONFIGURED", "GitHub OAuth 尚未配置"); }
    private String normalizeClient(String value) {
        String normalized = value == null || value.isBlank() ? "WEB" : value.trim().toUpperCase();
        if (!"WEB".equals(normalized) && !"MOBILE".equals(normalized)) throw new ApiException(HttpStatus.BAD_REQUEST,
                "INVALID_ARGUMENT", "client 只支持 WEB 或 MOBILE");
        return normalized;
    }
    private byte[] hash(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); } }
    private LocalDateTime now() { return LocalDateTime.now(clock); }
    private OffsetDateTime toOffset(LocalDateTime value) { return value == null ? null : OffsetDateTime.of(value, ZoneOffset.UTC); }
    private List<String> parseScopes(String value) {
        return value == null || value.isBlank() ? List.of() : java.util.Arrays.stream(value.split("[,\\s]+"))
                .map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
    }
    private boolean hasScope(List<String> scopes, String scope) {
        return scopes != null && scopes.stream().anyMatch(scope::equalsIgnoreCase);
    }

    public record CallbackResult(String client) { }
    public record PersonalCredential(String accessToken, long githubUserId, String githubLogin,
                                     List<String> scopes) {
        public boolean hasScope(String scope) {
            return scopes != null && scopes.stream().anyMatch(scope::equalsIgnoreCase);
        }
    }
    private record StateContext(UUID userId, String client) { }

    /** 带有已校验客户端类型的回调失败，用于把错误回跳到正确端。 */
    public static final class CallbackApiException extends ApiException {
        private final String client;

        public CallbackApiException(String client, ApiException cause) {
            super(cause.status(), cause.code(), cause.getMessage(), cause.details());
            this.client = client;
        }

        public String client() { return client; }
    }
}
